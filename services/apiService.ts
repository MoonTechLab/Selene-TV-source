import AppDataService from './appDataService';
import toastService from './toastService';
import { FavoriteItem, PlayRecord, SearchResult, SearchResource } from '../types/entities';

/**
 * API响应结果类
 */
export class ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
  statusCode?: number;

  constructor({
    success,
    data,
    message,
    statusCode,
  }: {
    success: boolean;
    data?: T;
    message?: string;
    statusCode?: number;
  }) {
    this.success = success;
    this.data = data;
    this.message = message;
    this.statusCode = statusCode;
  }

  static success<T>(data: T, statusCode?: number): ApiResponse<T> {
    return new ApiResponse<T>({
      success: true,
      data,
      statusCode,
    });
  }

  static error<T>(message: string, statusCode?: number): ApiResponse<T> {
    return new ApiResponse<T>({
      success: false,
      message,
      statusCode,
    });
  }
}

/**
 * 通用API请求服务
 */
class ApiService {
  private static readonly _timeout = 30000; // 30 seconds

  /**
   * 获取基础URL
   */
  private static async _getBaseUrl(): Promise<string | null> {
    return await AppDataService.getServerUrl();
  }

  /**
   * 获取认证cookies
   */
  private static async _getCookies(): Promise<string | null> {
    return await AppDataService.getCookies();
  }

  /**
   * 构建完整URL
   */
  private static async _buildUrl(endpoint: string): Promise<string> {
    const baseUrl = await this._getBaseUrl();
    if (!baseUrl) {
      throw new Error('服务器地址未配置，请先登录');
    }

    const cookies = await this._getCookies();
    if (!cookies) {
        throw new Error('未登录，请先登录');
    }

    // 确保baseUrl不以/结尾，endpoint以/开头
    const cleanBaseUrl = baseUrl.endsWith('/')
      ? baseUrl.substring(0, baseUrl.length - 1)
      : baseUrl;
    const cleanEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;

    return `${cleanBaseUrl}${cleanEndpoint}`;
  }

  /**
   * 构建请求头
   */
  private static async _buildHeaders({
    additionalHeaders,
    includeAuth = true,
  }: {
    additionalHeaders?: Record<string, string>;
    includeAuth?: boolean;
  } = {}): Promise<Record<string, string>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    // 添加认证cookies
    if (includeAuth) {
      const cookies = await this._getCookies();
      if (cookies) {
        headers['Cookie'] = cookies;
      }
    }

    // 添加额外头部
    if (additionalHeaders) {
      Object.assign(headers, additionalHeaders);
    }

    return headers;
  }

  /**
   * 处理响应
   */
  private static async _handleResponse<T>(
    response: Response,
    fromJson?: (data: any) => T,
  ): Promise<ApiResponse<T>> {
    // 处理401未授权
    if (response.status === 401) {
      // 设置登录状态为false
      await AppDataService.setHasLogin(false);

      // 显示toast提示
      toastService.error('登录状态失效，请重新登录');

      return ApiResponse.error('登录状态失效，请重新登录', 401);
    }

    // 处理其他错误状态码
    if (response.status < 200 || response.status >= 300) {
      let errorMessage = '请求失败';
      try {
        const errorData = await response.json();
        errorMessage = errorData.message || errorData.error || errorMessage;
      } catch (e) {
        // 如果解析失败，使用默认错误信息
        switch (response.status) {
          case 400:
            errorMessage = '请求参数错误';
            break;
          case 403:
            errorMessage = '没有权限访问';
            break;
          case 404:
            errorMessage = '请求的资源不存在';
            break;
          case 500:
            errorMessage = '服务器内部错误';
            break;
          default:
            errorMessage = `网络请求失败 (${response.status})`;
        }
      }

      return ApiResponse.error(errorMessage, response.status);
    }

    // 处理成功响应
    try {
      const responseData = await response.json();
      if (fromJson) {
        const data = fromJson(responseData);
        return ApiResponse.success(data, response.status);
      } else {
        return ApiResponse.success(responseData as T, response.status);
      }
    } catch (e) {
      return ApiResponse.error(
        `响应数据解析失败: ${e instanceof Error ? e.message : String(e)}`,
        response.status
      );
    }
  }

  /**
   * 创建带超时的fetch请求
   */
  private static async _fetchWithTimeout(
    url: string,
    options: RequestInit
  ): Promise<Response> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this._timeout);

    try {
      const response = await fetch(url, {
        ...options,
        signal: controller.signal,
      });
      clearTimeout(timeoutId);
      return response;
    } catch (error) {
      clearTimeout(timeoutId);
      throw error;
    }
  }

  /**
   * GET请求
   */
  static async get<T>(
    endpoint: string,
    {
      queryParameters,
      headers,
      fromJson,
    }: {
      queryParameters?: Record<string, string>;
      headers?: Record<string, string>;
      fromJson?: (data: any) => T;
    } = {}
  ): Promise<ApiResponse<T>> {
    try {
      let url = await this._buildUrl(endpoint);

      // 添加查询参数
      if (queryParameters && Object.keys(queryParameters).length > 0) {
        const params = new URLSearchParams(queryParameters);
        url = `${url}?${params.toString()}`;
      }

      const requestHeaders = await this._buildHeaders({ additionalHeaders: headers });

      const response = await this._fetchWithTimeout(url, {
        method: 'GET',
        headers: requestHeaders,
      });

      return await this._handleResponse(response, fromJson);
    } catch (e) {
      return ApiResponse.error(
        `网络请求异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * POST请求
   */
  static async post<T>(
    endpoint: string,
    {
      body,
      headers,
      fromJson,
    }: {
      body?: Record<string, any>;
      headers?: Record<string, string>;
      fromJson?: (data: any) => T;
    } = {}
  ): Promise<ApiResponse<T>> {
    try {
      const url = await this._buildUrl(endpoint);
      const requestHeaders = await this._buildHeaders({ additionalHeaders: headers });

      const response = await this._fetchWithTimeout(url, {
        method: 'POST',
        headers: requestHeaders,
        body: body ? JSON.stringify(body) : undefined,
      });

      return await this._handleResponse(response, fromJson);
    } catch (e) {
      return ApiResponse.error(
        `网络请求异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * PUT请求
   */
  static async put<T>(
    endpoint: string,
    {
      body,
      headers,
      fromJson,
    }: {
      body?: Record<string, any>;
      headers?: Record<string, string>;
      fromJson?: (data: any) => T;
    } = {}
  ): Promise<ApiResponse<T>> {
    try {
      const url = await this._buildUrl(endpoint);
      const requestHeaders = await this._buildHeaders({ additionalHeaders: headers });

      const response = await this._fetchWithTimeout(url, {
        method: 'PUT',
        headers: requestHeaders,
        body: body ? JSON.stringify(body) : undefined,
      });

      return await this._handleResponse(response, fromJson);
    } catch (e) {
      return ApiResponse.error(
        `网络请求异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * DELETE请求
   */
  static async delete<T>(
    endpoint: string,
    {
      headers,
      fromJson,
    }: {
      headers?: Record<string, string>;
      fromJson?: (data: any) => T;
    } = {}
  ): Promise<ApiResponse<T>> {
    try {
      const url = await this._buildUrl(endpoint);
      const requestHeaders = await this._buildHeaders({ additionalHeaders: headers });

      const response = await this._fetchWithTimeout(url, {
        method: 'DELETE',
        headers: requestHeaders,
      });

      return await this._handleResponse(response, fromJson);
    } catch (e) {
      return ApiResponse.error(
        `网络请求异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 获取收藏夹列表
   */
  static async getFavorites(): Promise<ApiResponse<FavoriteItem[]>> {
    try {
      const response = await this.get<Record<string, any>>('/api/favorites');

      if (response.success && response.data) {
        const data = response.data;
        const favorites: FavoriteItem[] = [];

        // 将Map转换为List并按save_time降序排序
        Object.entries(data).forEach(([key, itemData]) => {
          // 从key中分离source和id，格式为 "source+id"
          const parts = key.split('+');
          const source = parts.length > 1 ? parts[0] : '';
          const id = parts.length > 1 ? parts[1] : key;
          
          favorites.push({
            id,
            source: source,
            title: itemData.title,
            sourceName: itemData.source_name,
            year: itemData.year,
            cover: itemData.cover,
            totalEpisodes: itemData.total_episodes,
            saveTime: itemData.save_time,
            origin: itemData.origin,
          });
        });

        // 按save_time降序排序
        favorites.sort((a, b) => b.saveTime - a.saveTime);

        return ApiResponse.success(favorites, response.statusCode);
      } else {
        return ApiResponse.error(response.message || '获取收藏夹失败');
      }
    } catch (e) {
      return ApiResponse.error(
        `获取收藏夹异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 获取搜索历史
   */
  static async getSearchHistory(): Promise<ApiResponse<string[]>> {
    try {
      const response = await this.get<string[]>('/api/searchhistory', {
        fromJson: (data) => (data as string[]),
      });

      if (response.success && response.data) {
        return ApiResponse.success(response.data, response.statusCode);
      } else {
        return ApiResponse.error(response.message || '获取搜索历史失败');
      }
    } catch (e) {
      return ApiResponse.error(
        `获取搜索历史异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 添加搜索历史
   */
  static async addSearchHistory(query: string): Promise<ApiResponse<void>> {
    try {
      const response = await this.post<void>('/api/searchhistory', {
        body: { keyword: query },
      });

      return response;
    } catch (e) {
      return ApiResponse.error(
        `添加搜索历史异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 清空搜索历史
   */
  static async clearSearchHistory(): Promise<ApiResponse<void>> {
    try {
      const response = await this.delete<void>('/api/searchhistory');

      return response;
    } catch (e) {
      return ApiResponse.error(
        `清空搜索历史异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 删除单个搜索历史
   */
  static async deleteSearchHistory(query: string): Promise<ApiResponse<void>> {
    try {
      const encodedQuery = encodeURIComponent(query);
      const response = await this.delete<void>(
        `/api/searchhistory?keyword=${encodedQuery}`
      );

      return response;
    } catch (e) {
      return ApiResponse.error(
        `删除搜索历史异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 获取播放记录
   */
  static async getPlayRecords(): Promise<ApiResponse<Record<string, PlayRecord>>> {
    try {
      const response = await this.get<Record<string, any>>('/api/playrecords');

      if (response.success && response.data) {
        const data = response.data;
        const playRecords: Record<string, PlayRecord> = {};

        // 将响应数据转换为PlayRecord对象
        Object.entries(data).forEach(([key, itemData]) => {
          // 从key中分离source和id，格式为 "source+id"
          const parts = key.split('+');
          const source = parts.length > 1 ? parts[0] : '';
          const id = parts.length > 1 ? parts[1] : key;

          playRecords[key] = {
            id,
            source,
            title: itemData.title,
            sourceName: itemData.source_name,
            year: itemData.year,
            cover: itemData.cover,
            index: itemData.index,
            totalEpisodes: itemData.total_episodes,
            playTime: itemData.play_time,
            totalTime: itemData.total_time,
            saveTime: itemData.save_time,
            searchTitle: itemData.search_title,
          };
        });

        return ApiResponse.success(playRecords, response.statusCode);
      } else {
        return ApiResponse.error(response.message || '获取播放记录失败');
      }
    } catch (e) {
      return ApiResponse.error(
        `获取播放记录异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 保存播放记录
   */
  static async savePlayRecord(playRecord: PlayRecord): Promise<ApiResponse<void>> {
    try {
      // 构建正确的请求体格式
      const key = `${playRecord.source}+${playRecord.id}`;
      const body = {
        key,
        record: {
          id: playRecord.id,
          source: playRecord.source,
          title: playRecord.title,
          source_name: playRecord.sourceName,
          year: playRecord.year,
          cover: playRecord.cover,
          index: playRecord.index,
          total_episodes: playRecord.totalEpisodes,
          play_time: playRecord.playTime,
          total_time: playRecord.totalTime,
          save_time: playRecord.saveTime,
          search_title: playRecord.searchTitle,
        },
      };

      const response = await this.post<void>('/api/playrecords', {
        body,
      });

      return response;
    } catch (e) {
      return ApiResponse.error(
        `保存播放记录异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 删除播放记录
   */
  static async deletePlayRecord(
    source: string,
    id: string
  ): Promise<ApiResponse<void>> {
    try {
      const key = `${source}+${id}`;
      const encodedKey = encodeURIComponent(key);
      const response = await this.delete<void>(
        `/api/playrecords?key=${encodedKey}`
      );

      return response;
    } catch (e) {
      return ApiResponse.error(
        `删除播放记录异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 清空播放记录
   */
  static async clearPlayRecord(): Promise<ApiResponse<void>> {
    try {
      const response = await this.delete<void>('/api/playrecords');

      return response;
    } catch (e) {
      return ApiResponse.error(
        `清空播放记录异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 添加收藏
   */
  static async favorite(
    source: string,
    id: string,
    favoriteData: Record<string, any>
  ): Promise<ApiResponse<void>> {
    try {
      const key = `${source}+${id}`;
      const body = {
        key,
        favorite: favoriteData,
      };

      const response = await this.post<void>('/api/favorites', {
        body,
      });

      return response;
    } catch (e) {
      return ApiResponse.error(
        `收藏异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 取消收藏
   */
  static async unfavorite(source: string, id: string): Promise<ApiResponse<void>> {
    try {
      const key = `${source}+${id}`;
      const encodedKey = encodeURIComponent(key);
      const response = await this.delete<void>(
        `/api/favorites?key=${encodedKey}`
      );

      return response;
    } catch (e) {
      return ApiResponse.error(
        `取消收藏异常: ${e instanceof Error ? e.message : String(e)}`
      );
    }
  }

  /**
   * 获取视频详情
   */
  static async fetchSourceDetail(
    source: string,
    id: string
  ): Promise<SearchResult[]> {
    try {
      const response = await this.get<SearchResult>('/api/detail', {
        queryParameters: { source, id },
        fromJson: (data) => ({
          id: data.id,
          title: data.title,
          poster: data.poster,
          episodes: data.episodes || [],
          episodesTitles: data.episodes_titles || [],
          source: data.source,
          sourceName: data.source_name,
          class_: data.class,
          year: data.year,
          desc: data.desc,
          typeName: data.type_name,
          doubanId: data.douban_id,
        }),
      });

      if (response.success && response.data) {
        return [response.data];
      } else {
        console.log(`获取视频详情失败: ${response.message}`);
        return [];
      }
    } catch (e) {
      console.log(`获取视频详情失败: ${e}`);
      return [];
    }
  }

  /**
   * 搜索视频源数据
   */
  static async fetchSourcesData(query: string): Promise<SearchResult[]> {
    try {
      const response = await this.get<Record<string, any>>('/api/search', {
        queryParameters: { q: query.trim() },
        fromJson: (data) => data as Record<string, any>,
      });

      if (response.success && response.data) {
        const data = response.data;
        const results = (data.results as any[]) || [];

        // 直接返回所有搜索结果，不进行过滤
        return results.map((item) => ({
          id: item.id,
          title: item.title,
          poster: item.poster,
          episodes: item.episodes || [],
          episodesTitles: item.episodes_titles || [],
          source: item.source,
          sourceName: item.source_name,
          class_: item.class,
          year: item.year,
          desc: item.desc,
          typeName: item.type_name,
          doubanId: item.douban_id,
        }));
      } else {
        console.log(`搜索失败: ${response.message}`);
        return [];
      }
    } catch (e) {
      console.log(`搜索失败: ${e}`);
      return [];
    }
  }

  /**
   * 获取搜索资源列表
   */
  static async getSearchResources(): Promise<SearchResource[]> {
    try {
      const response = await this.get<SearchResource[]>('/api/search/resources', {
        fromJson: (data) => {
          const list = data as any[];
          return list.map((item) => ({
            key: item.key,
            name: item.name,
            api: item.api,
            detail: item.detail,
            from: item.from,
            disabled: item.disabled,
          }));
        },
      });

      if (response.success && response.data) {
        return response.data;
      } else {
        console.log(`获取搜索源失败: ${response.message}`);
        return [];
      }
    } catch (e) {
      console.log(`获取搜索源失败: ${e}`);
      return [];
    }
  }
}

export default ApiService;
