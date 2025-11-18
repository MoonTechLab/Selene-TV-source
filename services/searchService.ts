import { SearchResult, SearchResource } from '../types/entities';
import ApiService from './apiService';
import DownstreamService from './downStreamService';

/**
 * 搜索进度信息
 */
export interface SearchProgress {
  totalSources: number;
  completedSources: number;
  currentSource: string | null;
  isComplete: boolean;
  error?: string;
}

/**
 * 搜索事件类型
 */
export type SearchEventType = 'results' | 'progress' | 'error' | 'complete';

/**
 * 搜索事件监听器
 */
export type SearchEventListener<T = any> = (data: T) => void;

/**
 * 流式搜索服务
 * 实现增量搜索结果推送
 */
class SearchService {
  private static _isSearching = false;
  private static _currentQuery: string | null = null;
  private static _completedSources = 0;
  private static _totalSources = 0;
  private static _sourceErrors: Map<string, string> = new Map();
  private static _abortController: AbortController | null = null;
  private static _timeoutTimer: ReturnType<typeof setTimeout> | null = null;

  // 事件监听器
  private static _listeners: Map<SearchEventType, Set<SearchEventListener>> = new Map();

  /**
   * 添加事件监听器
   */
  static addEventListener<T = any>(
    event: SearchEventType,
    listener: SearchEventListener<T>
  ): () => void {
    if (!this._listeners.has(event)) {
      this._listeners.set(event, new Set());
    }
    this._listeners.get(event)!.add(listener);

    // 返回取消监听的函数
    return () => {
      this._listeners.get(event)?.delete(listener);
    };
  }

  /**
   * 移除事件监听器
   */
  static removeEventListener(event: SearchEventType, listener: SearchEventListener): void {
    this._listeners.get(event)?.delete(listener);
  }

  /**
   * 触发事件
   */
  private static _emit<T = any>(event: SearchEventType, data: T): void {
    const listeners = this._listeners.get(event);
    if (listeners) {
      listeners.forEach((listener) => {
        try {
          listener(data);
        } catch (error) {
          console.error(`事件监听器执行失败 (${event}):`, error);
        }
      });
    }
  }

  /**
   * 是否正在搜索
   */
  static get isSearching(): boolean {
    return this._isSearching;
  }

  /**
   * 当前搜索查询
   */
  static get currentQuery(): string | null {
    return this._currentQuery;
  }

  /**
   * 获取源错误信息
   */
  static get sourceErrors(): Map<string, string> {
    return new Map(this._sourceErrors);
  }

  /**
   * 开始搜索
   */
  static async startSearch(query: string): Promise<void> {
    if (!query.trim()) {
      throw new Error('搜索查询不能为空');
    }

    // 如果已有搜索，先停止
    if (this._isSearching) {
      await this.stopSearch();
    }

    this._currentQuery = query.trim();
    this._isSearching = true;
    this._completedSources = 0;
    this._totalSources = 0;
    this._sourceErrors.clear();
    this._abortController = new AbortController();

    // 设置 15 秒超时
    this._timeoutTimer = setTimeout(() => {
      if (this._isSearching) {
        this._handleTimeout();
      }
    }, 15000);

    try {
      // TODO: 检查是否启用本地搜索模式
      // 暂时直接使用本地搜索
      await this._localSearch(query.trim());
    } catch (error) {
      this._emit('error', `搜索失败: ${error instanceof Error ? error.message : String(error)}`);
      this._isSearching = false;
    }
  }

  /**
   * 本地搜索（并发搜索所有资源）
   */
  private static async _localSearch(query: string): Promise<void> {
    try {
      // 获取搜索资源列表
      const allResources = await ApiService.getSearchResources();

      // 过滤掉禁用的资源
      const resources = allResources.filter((resource) => !resource.disabled);

      if (resources.length === 0) {
        this._emit('error', '没有可用的搜索资源');
        this._isSearching = false;
        return;
      }

      this._totalSources = resources.length;
      this._completedSources = 0;

      // 发送初始进度
      this._emit<SearchProgress>('progress', {
        totalSources: this._totalSources,
        completedSources: 0,
        currentSource: null,
        isComplete: false,
      });

      // 并发搜索所有资源
      const searchPromises = resources.map((resource) =>
        this._searchSingleResource(resource, query)
      );

      // 等待所有搜索完成
      await Promise.all(searchPromises);

      // 发送完成事件
      this._emit<SearchProgress>('progress', {
        totalSources: this._totalSources,
        completedSources: this._totalSources,
        currentSource: null,
        isComplete: true,
      });

      this._emit('complete', null);
      this._isSearching = false;

      // 清理超时定时器
      if (this._timeoutTimer) {
        clearTimeout(this._timeoutTimer);
        this._timeoutTimer = null;
      }
    } catch (error) {
      this._emit('error', `本地搜索异常: ${error instanceof Error ? error.message : String(error)}`);
      this._isSearching = false;
    }
  }

  /**
   * 搜索单个资源
   */
  private static async _searchSingleResource(
    resource: SearchResource,
    query: string
  ): Promise<void> {
    try {
      // 创建一个 20 秒超时的 Promise
      const timeoutPromise = new Promise<SearchResult[]>((_, reject) => {
        setTimeout(() => reject(new Error('搜索超时（20秒）')), 20000);
      });

      // 调用 searchFromApi 并设置 20 秒超时
      const results = await Promise.race([
        DownstreamService.searchFromApi(resource, query),
        timeoutPromise,
      ]);

      // 增加完成计数
      this._completedSources++;

      // 发送结果事件（只发送增量结果）
      if (results.length > 0) {
        this._emit<SearchResult[]>('results', results);
      }

      // 发送进度更新
      this._emit<SearchProgress>('progress', {
        totalSources: this._totalSources,
        completedSources: this._completedSources,
        currentSource: resource.name,
        isComplete: false,
      });
    } catch (error) {
      // 错误处理
      this._completedSources++;
      const errorMessage = error instanceof Error ? error.message : String(error);
      this._sourceErrors.set(resource.key, errorMessage);

      // 发送错误进度更新
      this._emit<SearchProgress>('progress', {
        totalSources: this._totalSources,
        completedSources: this._completedSources,
        currentSource: resource.name,
        isComplete: false,
        error: errorMessage,
      });
    }
  }

  /**
   * 处理超时
   */
  private static _handleTimeout(): void {
    // 如果完成源数小于总源数，说明有些源没有完成
    if (this._completedSources < this._totalSources) {
      this._completedSources = this._totalSources;
    }

    // 发送超时状态
    this._emit<SearchProgress>('progress', {
      totalSources: this._totalSources,
      completedSources: this._completedSources,
      currentSource: null,
      isComplete: true,
    });

    this._emit('error', '搜索超时（15秒）');
    this._emit('complete', null);
    this._isSearching = false;

    // 清理
    if (this._timeoutTimer) {
      clearTimeout(this._timeoutTimer);
      this._timeoutTimer = null;
    }
  }

  /**
   * 停止搜索
   */
  static async stopSearch(): Promise<void> {
    if (this._abortController) {
      this._abortController.abort();
      this._abortController = null;
    }

    if (this._timeoutTimer) {
      clearTimeout(this._timeoutTimer);
      this._timeoutTimer = null;
    }

    this._isSearching = false;
    this._currentQuery = null;
  }

  /**
   * 合并和去重搜索结果
   */
  static deduplicateResults(results: SearchResult[]): SearchResult[] {
    const seen = new Set<string>();
    const deduplicated: SearchResult[] = [];

    for (const result of results) {
      // 使用 source + id 作为唯一标识
      const key = `${result.source}+${result.id}`;

      if (!seen.has(key)) {
        seen.add(key);
        deduplicated.push(result);
      }
    }

    return deduplicated;
  }

  /**
   * 清理所有监听器
   */
  static dispose(): void {
    this.stopSearch();
    this._listeners.clear();
  }
}

export default SearchService;
