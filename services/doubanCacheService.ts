import { Paths, Directory, File } from 'expo-file-system';

/**
 * 缓存项数据结构
 */
interface CacheItem<T> {
  data: T;
  timestamp: number;
  expiration: number;
}

/**
 * 豆瓣缓存服务类
 */
class DoubanCacheService {
  private static instance: DoubanCacheService;
  
  /** 内存缓存（保存为原始JSON结构，读取时再解码为目标类型） */
  private memoryCache: Map<string, CacheItem<any>> = new Map();
  
  /** 缓存文件目录 */
  private cacheDir: Directory | null = null;
  
  /** 定期清理定时器 */
  private cleanupInterval: ReturnType<typeof setInterval> | null = null;

  private constructor() {}

  static getInstance(): DoubanCacheService {
    if (!DoubanCacheService.instance) {
      DoubanCacheService.instance = new DoubanCacheService();
    }
    return DoubanCacheService.instance;
  }

  /**
   * 初始化缓存服务
   */
  async init(): Promise<void> {
    try {
      this.cacheDir = new Directory(Paths.cache, 'douban_cache');
      
      if (!this.cacheDir.exists) {
        this.cacheDir.create();
      }
      
      // 启动时清理过期缓存
      await this.cleanExpiredCache();
    } catch (e) {
      console.error('豆瓣缓存服务初始化失败:', e);
    }
  }

  /**
   * 生成缓存键
   */
  private generateCacheKey(prefix: string, params: Record<string, any>): string {
    const sortedKeys = Object.keys(params).sort();
    const paramString = sortedKeys.map(key => `${key}=${params[key]}`).join('&');
    return `${prefix}_${paramString}`;
  }

  /**
   * 检查缓存是否过期
   */
  private isExpired(cacheItem: CacheItem<any>): boolean {
    return Date.now() - cacheItem.timestamp > cacheItem.expiration;
  }

  /**
   * 获取缓存
   * decode: 将原始 JSON 数据(dynamic，可为 List/Map 等) 转换为目标类型 T
   */
  async get<T>(key: string, decode: (data: any) => T): Promise<T | null> {
    try {
      // 先检查内存缓存
      if (this.memoryCache.has(key)) {
        const cacheItem = this.memoryCache.get(key)!;
        
        if (!this.isExpired(cacheItem)) {
          const raw = cacheItem.data;
          
          // 发现旧格式（Map 且含 items），直接清除并视为未命中
          if (raw && typeof raw === 'object' && 'items' in raw) {
            this.memoryCache.delete(key);
            if (this.cacheDir) {
              try {
                const file = new File(this.cacheDir, `${key}.json`);
                if (file.exists) {
                  file.delete();
                }
              } catch (_) {}
            }
          } else {
            // 使用解码器将原始 JSON 转成目标类型
            try {
              return decode(raw);
            } catch (e) {
              // 解码失败，删除缓存
              this.memoryCache.delete(key);
              if (this.cacheDir) {
                try {
                  const file = new File(this.cacheDir, `${key}.json`);
                  if (file.exists) {
                    file.delete();
                  }
                } catch (_) {}
              }
              throw e;
            }
          }
        } else {
          // 过期则删除
          this.memoryCache.delete(key);
        }
      }

      // 检查磁盘缓存
      if (this.cacheDir) {
        const file = new File(this.cacheDir, `${key}.json`);
        
        if (file.exists) {
          try {
            const jsonString = await file.text();
            const jsonData = JSON.parse(jsonString);
            
            if (typeof jsonData !== 'object' || !jsonData) {
              try {
                file.delete();
              } catch (_) {}
              return null;
            }

            // 检查必需的字段
            if (!('data' in jsonData) || !('timestamp' in jsonData) || !('expiration' in jsonData)) {
              try {
                file.delete();
              } catch (_) {}
              return null;
            }

            const cacheItem: CacheItem<any> = {
              data: jsonData.data,
              timestamp: Number(jsonData.timestamp),
              expiration: Number(jsonData.expiration),
            };

            if (!this.isExpired(cacheItem)) {
              const raw = cacheItem.data;
              
              // 发现旧格式（Map 且含 items），直接删除并视为未命中
              if (raw && typeof raw === 'object' && 'items' in raw) {
                try {
                  file.delete();
                } catch (_) {}
              } else {
                // 重新加载到内存缓存
                this.memoryCache.set(key, cacheItem);
                try {
                  return decode(raw);
                } catch (e) {
                  // 解码失败，删除缓存
                  this.memoryCache.delete(key);
                  try {
                    file.delete();
                  } catch (_) {}
                  throw e;
                }
              }
            } else {
              // 过期则删除文件
              file.delete();
            }
          } catch (e) {
            // 文件内容不合法或旧格式，直接删除避免反复报错
            try {
              file.delete();
            } catch (_) {}
          }
        }
      }
    } catch (e) {
      // 静默处理缓存读取错误
    }
    
    return null;
  }

  /**
   * 设置缓存
   */
  async set<T>(key: string, data: T, expiration: number): Promise<void> {
    try {
      const cacheItem: CacheItem<T> = {
        data,
        timestamp: Date.now(),
        expiration,
      };

      // 保存到内存缓存
      this.memoryCache.set(key, cacheItem);

      // 保存到磁盘缓存
      if (this.cacheDir) {
        const file = new File(this.cacheDir, `${key}.json`);
        const jsonData = JSON.stringify(cacheItem);
        await file.write(jsonData);
      }
    } catch (e) {
      console.error('设置豆瓣缓存失败:', e);
    }
  }

  /**
   * 删除缓存
   */
  async delete(key: string): Promise<void> {
    try {
      // 从内存缓存删除
      this.memoryCache.delete(key);

      // 从磁盘缓存删除
      if (this.cacheDir) {
        const file = new File(this.cacheDir, `${key}.json`);
        if (file.exists) {
          file.delete();
        }
      }
    } catch (e) {
      console.error('删除豆瓣缓存失败:', e);
    }
  }

  /**
   * 清理过期缓存
   */
  private async cleanExpiredCache(): Promise<void> {
    try {
      // 清理内存缓存
      const expiredKeys: string[] = [];
      this.memoryCache.forEach((item, key) => {
        if (this.isExpired(item)) {
          expiredKeys.push(key);
        }
      });
      
      for (const key of expiredKeys) {
        this.memoryCache.delete(key);
      }

      // 清理磁盘缓存
      if (this.cacheDir && this.cacheDir.exists) {
        const items = this.cacheDir.list();
        
        for (const item of items) {
          if (item instanceof File && item.uri.endsWith('.json')) {
            try {
              const jsonString = await item.text();
              const jsonData = JSON.parse(jsonString);
              
              const timestamp = Number(jsonData.timestamp);
              const expiration = Number(jsonData.expiration);
              
              if (Date.now() - timestamp > expiration) {
                item.delete();
              }
            } catch (e) {
              // 如果文件损坏，直接删除
              item.delete();
            }
          }
        }
      }
    } catch (e) {
      console.error('清理过期豆瓣缓存失败:', e);
    }
  }

  /**
   * 清空所有缓存
   */
  async clearAll(): Promise<void> {
    try {
      // 清空内存缓存
      this.memoryCache.clear();

      // 清空磁盘缓存
      if (this.cacheDir && this.cacheDir.exists) {
        const items = this.cacheDir.list();
        for (const item of items) {
          if (item instanceof File) {
            item.delete();
          }
        }
      }
    } catch (e) {
      console.error('清空所有豆瓣缓存失败:', e);
    }
  }

  /**
   * 定期清理过期缓存（建议在应用启动时调用）
   */
  startPeriodicCleanup(): void {
    // 每小时清理一次过期缓存
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
    }
    
    this.cleanupInterval = setInterval(() => {
      this.cleanExpiredCache();
    }, 60 * 60 * 1000); // 1 hour
  }

  /**
   * 停止定期清理
   */
  stopPeriodicCleanup(): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }
  }

  // ==================== 豆瓣服务专用缓存方法 ====================

  /**
   * 为豆瓣分类数据生成缓存键
   */
  generateDoubanCategoryCacheKey(params: {
    kind: string;
    category: string;
    type: string;
    pageLimit: number;
    page: number;
  }): string {
    return this.generateCacheKey('douban_category', params);
  }

  /**
   * 为豆瓣推荐数据生成缓存键
   */
  generateDoubanRecommendsCacheKey(params: {
    kind: string;
    category: string;
    format: string;
    region: string;
    year: string;
    platform: string;
    sort: string;
    label: string;
    pageLimit: number;
    page: number;
  }): string {
    return this.generateCacheKey('douban_recommends', params);
  }

  /**
   * 为豆瓣详情数据生成缓存键
   */
  generateDoubanDetailsCacheKey(params: {
    doubanId: string;
  }): string {
    return this.generateCacheKey('douban_details', params);
  }

  /**
   * 为 Bangumi 详情数据生成缓存键
   */
  generateBangumiDetailsCacheKey(params: {
    bangumiId: string;
  }): string {
    return this.generateCacheKey('bangumi_details', params);
  }

  /**
   * 清理所有缓存（用于调试）
   */
  async clearAllCache(): Promise<void> {
    try {
      this.memoryCache.clear();
      
      if (this.cacheDir && this.cacheDir.exists) {
        this.cacheDir.delete();
        this.cacheDir = new Directory(Paths.cache, 'douban_cache');
        this.cacheDir.create();
      }
    } catch (e) {
      // 静默处理清理错误
    }
  }
}

// 导出单例实例
export default DoubanCacheService.getInstance();
