import { SearchResult } from '../types/entities';

/**
 * 缓存页面状态枚举
 */
export enum CachedPageStatus {
  OK = 'ok',
  TIMEOUT = 'timeout',
  FORBIDDEN = 'forbidden',
}

/**
 * 缓存条目类
 */
export class CachedPageEntry {
  expiresAt: number;
  status: CachedPageStatus;
  data: SearchResult[];
  pageCount?: number; // 仅第一页可选存储

  constructor({
    expiresAt,
    status,
    data,
    pageCount,
  }: {
    expiresAt: number;
    status: CachedPageStatus;
    data: SearchResult[];
    pageCount?: number;
  }) {
    this.expiresAt = expiresAt;
    this.status = status;
    this.data = data;
    this.pageCount = pageCount;
  }
}

/**
 * 缓存配置
 */
const SEARCH_CACHE_TTL_MS = 10 * 60 * 1000; // 10分钟
const CACHE_CLEANUP_INTERVAL_MS = 5 * 60 * 1000; // 5分钟清理一次
const MAX_CACHE_SIZE = 1000; // 最大缓存条目数量

/**
 * 本地搜索缓存服务
 * 实现搜索结果的分页缓存管理
 */
class LocalSearchCacheService {
  private static _instance: LocalSearchCacheService;
  private _searchCache: Map<string, CachedPageEntry> = new Map();
  private _cleanupTimer?: ReturnType<typeof setInterval>;
  private _lastCleanupTime: number = 0;

  private constructor() {}

  static getInstance(): LocalSearchCacheService {
    if (!LocalSearchCacheService._instance) {
      LocalSearchCacheService._instance = new LocalSearchCacheService();
    }
    return LocalSearchCacheService._instance;
  }

  /**
   * 生成搜索缓存键：source + query + page
   */
  private _makeSearchCacheKey(sourceKey: string, query: string, page: number): string {
    return `${sourceKey}::${query.trim()}::${page}`;
  }

  /**
   * 获取缓存的搜索页面数据
   */
  getCachedSearchPage(
    sourceKey: string,
    query: string,
    page: number
  ): CachedPageEntry | null {
    const key = this._makeSearchCacheKey(sourceKey, query, page);
    const entry = this._searchCache.get(key);

    if (!entry) return null;

    // 检查是否过期
    if (entry.expiresAt <= Date.now()) {
      this._searchCache.delete(key);
      return null;
    }

    return entry;
  }

  /**
   * 设置缓存的搜索页面数据
   */
  setCachedSearchPage(
    sourceKey: string,
    query: string,
    page: number,
    status: CachedPageStatus,
    data: SearchResult[],
    pageCount?: number
  ): void {
    // 惰性启动自动清理
    this._ensureAutoCleanupStarted();

    // 惰性清理：每次写入时检查是否需要清理
    const now = Date.now();
    if (now - this._lastCleanupTime > CACHE_CLEANUP_INTERVAL_MS) {
      this._performCacheCleanup();
    }

    const key = this._makeSearchCacheKey(sourceKey, query, page);
    this._searchCache.set(
      key,
      new CachedPageEntry({
        expiresAt: now + SEARCH_CACHE_TTL_MS,
        status,
        data,
        pageCount,
      })
    );
  }

  /**
   * 确保自动清理已启动（惰性初始化）
   */
  private _ensureAutoCleanupStarted(): void {
    if (!this._cleanupTimer) {
      this._startAutoCleanup();
    }
  }

  /**
   * 智能清理过期的缓存条目
   */
  private _performCacheCleanup(): {
    expired: number;
    total: number;
    sizeLimited: number;
  } {
    const now = Date.now();
    const keysToDelete: string[] = [];
    let sizeLimitedDeleted = 0;

    // 1. 清理过期条目
    this._searchCache.forEach((entry, key) => {
      if (entry.expiresAt <= now) {
        keysToDelete.push(key);
      }
    });

    const expiredCount = keysToDelete.length;
    keysToDelete.forEach((key) => {
      this._searchCache.delete(key);
    });

    // 2. 如果缓存大小超限，清理最老的条目（LRU策略）
    if (this._searchCache.size > MAX_CACHE_SIZE) {
      const entries = Array.from(this._searchCache.entries());
      // 按照过期时间排序，最早过期的在前面
      entries.sort((a, b) => a[1].expiresAt - b[1].expiresAt);

      const toRemove = this._searchCache.size - MAX_CACHE_SIZE;
      for (let i = 0; i < toRemove; i++) {
        this._searchCache.delete(entries[i][0]);
        sizeLimitedDeleted++;
      }
    }

    this._lastCleanupTime = now;

    return {
      expired: expiredCount,
      total: this._searchCache.size,
      sizeLimited: sizeLimitedDeleted,
    };
  }

  /**
   * 启动自动清理定时器
   */
  private _startAutoCleanup(): void {
    if (this._cleanupTimer) return; // 避免重复启动

    this._cleanupTimer = setInterval(() => {
      this._performCacheCleanup();
    }, CACHE_CLEANUP_INTERVAL_MS);
  }

  /**
   * 停止自动清理（用于资源清理）
   */
  dispose(): void {
    if (this._cleanupTimer) {
      clearInterval(this._cleanupTimer);
      this._cleanupTimer = undefined;
    }
    this._searchCache.clear();
  }

  /**
   * 清空所有缓存
   */
  clearCache(): void {
    this._searchCache.clear();
  }

  /**
   * 获取当前缓存大小
   */
  get cacheSize(): number {
    return this._searchCache.size;
  }
}

// 导出单例实例
export default LocalSearchCacheService.getInstance();
