import ApiService from './apiService';
import { FavoriteItem, PlayRecord } from '../types/entities';

/**
 * 数据操作结果类
 */
export class DataOperationResult<T> {
  success: boolean;
  data?: T;
  message?: string;

  constructor({
    success,
    data,
    message,
  }: {
    success: boolean;
    data?: T;
    message?: string;
  }) {
    this.success = success;
    this.data = data;
    this.message = message;
  }

  static success<T>(data: T): DataOperationResult<T> {
    return new DataOperationResult<T>({
      success: true,
      data,
    });
  }

  static error<T>(message: string): DataOperationResult<T> {
    return new DataOperationResult<T>({
      success: false,
      message,
    });
  }
}

/**
 * 用户数据缓存服务
 * 实现播放记录、收藏夹、搜索历史的缓存管理
 */
class UserDataCacheService {
  private static _instance: UserDataCacheService;
  private _cache: Map<string, any> = new Map();

  private constructor() {}

  static getInstance(): UserDataCacheService {
    if (!UserDataCacheService._instance) {
      UserDataCacheService._instance = new UserDataCacheService();
    }
    return UserDataCacheService._instance;
  }

  // ==================== 缓存基础操作 ====================

  /**
   * 获取缓存数据
   */
  getCache<T>(key: string): T | null {
    return this._cache.get(key) ?? null;
  }

  /**
   * 设置缓存数据
   */
  setCache<T>(key: string, data: T): void {
    this._cache.set(key, data);
  }

  /**
   * 清除指定缓存
   */
  clearCache(key: string): void {
    this._cache.delete(key);
  }

  /**
   * 清除所有缓存
   */
  clearAllCache(): void {
    this._cache.clear();
  }

  // ==================== 播放记录操作 ====================

  /**
   * 获取播放记录（优先从缓存）
   */
  async getPlayRecords(): Promise<DataOperationResult<PlayRecord[]>> {
    const cacheKey = 'play_records';
    
    // 先检查缓存
    const cachedData = this.getCache<PlayRecord[]>(cacheKey);
    if (cachedData !== null) {
      return DataOperationResult.success(cachedData);
    }

    // 缓存未命中，直接走接口并保存到缓存
    return await this.getPlayRecordsDirect();
  }

  /**
   * 直接走接口并保存到缓存
   */
  async getPlayRecordsDirect(): Promise<DataOperationResult<PlayRecord[]>> {
    const cacheKey = 'play_records';

    try {
      const response = await ApiService.getPlayRecords();
      if (response.success && response.data) {
        const records: PlayRecord[] = [];
        
        Object.entries(response.data).forEach(([id, data]) => {
          try {
            records.push(data);
          } catch (e) {
            // 忽略解析失败的记录
          }
        });

        // 按save_time降序排列
        records.sort((a, b) => b.saveTime - a.saveTime);

        // 缓存数据
        this.setCache(cacheKey, records);
        return DataOperationResult.success(records);
      }
    } catch (e) {
      return DataOperationResult.error(`获取播放记录失败: ${e instanceof Error ? e.message : String(e)}`);
    }

    return DataOperationResult.error('获取播放记录失败');
  }

  /**
   * 刷新播放记录
   */
  async refreshPlayRecords(): Promise<void> {
    const cacheKey = 'play_records';

    try {
      const response = await ApiService.getPlayRecords();
      if (response.success && response.data) {
        const records: PlayRecord[] = [];
        
        Object.entries(response.data).forEach(([id, data]) => {
          try {
            records.push(data);
          } catch (e) {
            // 忽略解析失败的记录
          }
        });

        // 按save_time降序排列
        records.sort((a, b) => b.saveTime - a.saveTime);

        // 更新缓存数据
        this.setCache(cacheKey, records);
      }
    } catch (e) {
      // 静默处理错误，不影响主流程
    }
  }

  /**
   * 保存播放记录
   */
  async savePlayRecord(playRecord: PlayRecord): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this._addPlayRecordToCache(playRecord);

    try {
      const response = await ApiService.savePlayRecord(playRecord);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '保存播放记录失败');
      }
    } catch (e) {
      return DataOperationResult.error(`保存播放记录异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 删除播放记录
   */
  async deletePlayRecord(source: string, id: string): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this._removePlayRecordFromCache(source, id);

    try {
      const response = await ApiService.deletePlayRecord(source, id);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '删除播放记录失败');
      }
    } catch (e) {
      return DataOperationResult.error(`删除播放记录异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 清空播放记录
   */
  async clearPlayRecord(): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this.clearCache('play_records');

    try {
      const response = await ApiService.clearPlayRecord();
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '清空播放记录失败');
      }
    } catch (e) {
      return DataOperationResult.error(`清空播放记录异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 添加播放记录到缓存
   */
  private _addPlayRecordToCache(playRecord: PlayRecord): void {
    const cacheKey = 'play_records';
    const cachedData = this.getCache<PlayRecord[]>(cacheKey);
    let records: PlayRecord[];

    if (cachedData !== null) {
      // 移除相同source+id的记录
      records = cachedData.filter(
        (record) => !(record.source === playRecord.source && record.id === playRecord.id)
      );
      // 添加新记录
      records.push(playRecord);
    } else {
      records = [playRecord];
    }

    // 按save_time降序排列
    records.sort((a, b) => b.saveTime - a.saveTime);

    // 更新缓存
    this.setCache(cacheKey, records);
  }

  /**
   * 从缓存中移除播放记录
   */
  private _removePlayRecordFromCache(source: string, id: string): void {
    const cacheKey = 'play_records';
    const cachedData = this.getCache<PlayRecord[]>(cacheKey);

    if (cachedData !== null) {
      // 创建新的列表，排除要删除的记录
      const updatedRecords = cachedData.filter(
        (record) => !(record.source === source && record.id === id)
      );
      // 更新缓存
      this.setCache(cacheKey, updatedRecords);
    }
  }

  // ==================== 收藏夹操作 ====================

  /**
   * 获取收藏夹（优先从缓存）
   */
  async getFavorites(): Promise<DataOperationResult<FavoriteItem[]>> {
    const cacheKey = 'favorites';
    
    // 先检查缓存
    const cachedData = this.getCache<FavoriteItem[]>(cacheKey);
    if (cachedData !== null) {
      // 过滤掉 origin=live 的数据
      const filteredData = cachedData.filter((item) => item.origin !== 'live');
      return DataOperationResult.success(filteredData);
    }

    // 缓存未命中，直接走接口并保存到缓存
    return await this.getFavoritesDirect();
  }

  /**
   * 直接走接口并保存到缓存
   */
  async getFavoritesDirect(): Promise<DataOperationResult<FavoriteItem[]>> {
    const cacheKey = 'favorites';

    try {
      const response = await ApiService.getFavorites();
      if (response.success && response.data) {
        // 过滤掉 origin=live 的数据
        const filteredData = response.data.filter((item) => item.origin !== 'live');
        
        // 缓存过滤后的数据
        this.setCache(cacheKey, filteredData);
        return DataOperationResult.success(filteredData);
      }
    } catch (e) {
      return DataOperationResult.error(`获取收藏夹失败: ${e instanceof Error ? e.message : String(e)}`);
    }

    return DataOperationResult.error('获取收藏夹失败');
  }

  /**
   * 刷新收藏夹
   */
  async refreshFavorites(): Promise<void> {
    const cacheKey = 'favorites';

    try {
      const response = await ApiService.getFavorites();
      if (response.success && response.data) {
        // 过滤掉 origin=live 的数据
        const filteredData = response.data.filter((item) => item.origin !== 'live');
        
        // 更新缓存数据
        this.setCache(cacheKey, filteredData);
      }
    } catch (e) {
      // 静默处理错误，不影响主流程
    }
  }

  /**
   * 添加收藏
   */
  async addFavorite(
    source: string,
    id: string,
    favoriteData: Record<string, any>
  ): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this._addFavoriteToCache(source, id, favoriteData);

    try {
      const response = await ApiService.favorite(source, id, favoriteData);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '添加收藏失败');
      }
    } catch (e) {
      return DataOperationResult.error(`添加收藏异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 取消收藏
   */
  async removeFavorite(source: string, id: string): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this._removeFavoriteFromCache(source, id);

    try {
      const response = await ApiService.unfavorite(source, id);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '取消收藏失败');
      }
    } catch (e) {
      return DataOperationResult.error(`取消收藏异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 同步检查是否已收藏
   */
  isFavoritedSync(source: string, id: string): boolean {
    try {
      const favorites = this._getCachedFavorites();
      if (!favorites || favorites.length === 0) return false;

      // 根据 source+id 检查是否在收藏列表中
      const key = `${source}+${id}`;
      return favorites.some((favorite) => `${favorite.source}+${favorite.id}` === key);
    } catch (e) {
      return false;
    }
  }

  /**
   * 从缓存中移除收藏
   */
  private _removeFavoriteFromCache(source: string, id: string): void {
    const cacheKey = 'favorites';
    const cachedData = this.getCache<FavoriteItem[]>(cacheKey);

    if (cachedData !== null) {
      // 创建新的列表，排除要删除的收藏项目
      const updatedFavorites = cachedData.filter(
        (favorite) => !(favorite.source === source && favorite.id === id)
      );
      // 更新缓存
      this.setCache(cacheKey, updatedFavorites);
    }
  }

  /**
   * 添加收藏到缓存
   */
  private _addFavoriteToCache(
    source: string,
    id: string,
    favoriteData: Record<string, any>
  ): void {
    const cacheKey = 'favorites';
    const cachedData = this.getCache<FavoriteItem[]>(cacheKey);

    if (cachedData !== null) {
      // 检查是否已存在相同的收藏项目
      const existingIndex = cachedData.findIndex(
        (favorite) => favorite.source === source && favorite.id === id
      );

      if (existingIndex === -1) {
        // 不存在，创建新的收藏项目并添加到列表开头
        const newFavorite: FavoriteItem = {
          id,
          source,
          title: favoriteData.title ?? '',
          sourceName: favoriteData.source_name ?? '',
          year: favoriteData.year ?? '',
          cover: favoriteData.cover ?? '',
          totalEpisodes: favoriteData.total_episodes ?? 0,
          saveTime: favoriteData.save_time ?? Date.now(),
          origin: '', // 默认为空，表示非直播源
        };

        // 添加到列表开头，保持按save_time降序排列
        const updatedFavorites = [newFavorite, ...cachedData];
        this.setCache(cacheKey, updatedFavorites);
      }
    }
  }

  /**
   * 获取缓存的收藏列表
   */
  private _getCachedFavorites(): FavoriteItem[] | null {
    return this.getCache<FavoriteItem[]>('favorites');
  }

  // ==================== 搜索历史操作 ====================

  /**
   * 获取搜索历史（优先从缓存）
   */
  async getSearchHistory(): Promise<DataOperationResult<string[]>> {
    const cacheKey = 'search_history';
    
    // 先检查缓存
    const cachedData = this.getCache<string[]>(cacheKey);
    if (cachedData !== null) {
      return DataOperationResult.success(cachedData);
    }

    // 缓存未命中，直接走接口并保存到缓存
    return await this.getSearchHistoryDirect();
  }

  /**
   * 直接走接口并保存到缓存
   */
  async getSearchHistoryDirect(): Promise<DataOperationResult<string[]>> {
    const cacheKey = 'search_history';

    try {
      const response = await ApiService.getSearchHistory();
      if (response.success && response.data) {
        // 缓存数据
        this.setCache(cacheKey, response.data);
        return DataOperationResult.success(response.data);
      }
    } catch (e) {
      return DataOperationResult.error(`获取搜索历史失败: ${e instanceof Error ? e.message : String(e)}`);
    }

    return DataOperationResult.error('获取搜索历史失败');
  }

  /**
   * 刷新搜索历史
   */
  async refreshSearchHistory(): Promise<void> {
    const cacheKey = 'search_history';

    try {
      const response = await ApiService.getSearchHistory();
      if (response.success && response.data) {
        // 更新缓存数据
        this.setCache(cacheKey, response.data);
      }
    } catch (e) {
      // 静默处理错误，不影响主流程
    }
  }

  /**
   * 添加搜索历史
   */
  async addSearchHistory(query: string): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    const cacheKey = 'search_history';
    const cachedData = this.getCache<string[]>(cacheKey);

    if (cachedData !== null) {
      // 检查是否已存在相同的搜索词（区分大小写）
      const existingIndex = cachedData.findIndex((item) => item === query);

      if (existingIndex === -1) {
        // 不存在，添加到列表开头
        const updatedHistory = [query, ...cachedData];
        this.setCache(cacheKey, updatedHistory);
      } else {
        // 已存在，移动到列表开头（保持原始大小写）
        const existingItem = cachedData[existingIndex];
        const updatedHistory = [
          existingItem,
          ...cachedData.filter((item) => item !== query),
        ];
        this.setCache(cacheKey, updatedHistory);
      }
    } else {
      // 没有缓存数据，创建新的历史记录
      this.setCache(cacheKey, [query]);
    }

    try {
      const response = await ApiService.addSearchHistory(query);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '添加搜索历史失败');
      }
    } catch (e) {
      return DataOperationResult.error(`添加搜索历史异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 删除搜索历史
   */
  async deleteSearchHistory(query: string): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    const cacheKey = 'search_history';
    const cachedData = this.getCache<string[]>(cacheKey);

    if (cachedData !== null) {
      // 创建新的列表，排除要删除的搜索词
      const updatedHistory = cachedData.filter((item) => item !== query);
      this.setCache(cacheKey, updatedHistory);
    }

    try {
      const response = await ApiService.deleteSearchHistory(query);
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '删除搜索历史失败');
      }
    } catch (e) {
      return DataOperationResult.error(`删除搜索历史异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 清空搜索历史
   */
  async clearSearchHistory(): Promise<DataOperationResult<void>> {
    // 优先操作缓存
    this.clearCache('search_history');

    try {
      const response = await ApiService.clearSearchHistory();
      if (response.success) {
        return DataOperationResult.success(undefined);
      } else {
        return DataOperationResult.error(response.message ?? '清空搜索历史失败');
      }
    } catch (e) {
      return DataOperationResult.error(`清空搜索历史异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }
}

// 导出单例实例
export default UserDataCacheService.getInstance();
