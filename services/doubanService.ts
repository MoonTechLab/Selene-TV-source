import { ApiResponse, ApiResponseHelper } from '@/types/api';
import {
  DoubanMovie,
  DoubanResponse,
  DoubanItem,
  DoubanDataSourceKey,
  DoubanKind,
  DoubanRecommendsParams,
} from '@/types/douban';
import doubanCacheService from './doubanCacheService';
import AppDataService from './appDataService';

/**
 * 豆瓣服务类
 */
class DoubanService {
  private cacheInitialized = false;

  /**
   * 初始化缓存服务
   */
  private async initCache(): Promise<void> {
    if (!this.cacheInitialized) {
      await doubanCacheService.init();
      this.cacheInitialized = true;
    }
  }
  /**
   * 将豆瓣 API 返回的 Item 转换为 DoubanMovie
   */
  private parseItemToMovie(item: DoubanItem): DoubanMovie {
    // 获取封面图片 URL
    const poster = item.pic?.large || item.pic?.normal || item.pic?.small || '';
    
    // 获取评分
    const rate = item.rating?.value?.toString() || item.rating?.star_count?.toString();
    
    // 从 card_subtitle 中提取年份（格式：2025 / 国家 / 类型 / 导演 / 演员）
    const year = item.card_subtitle?.split('/')[0]?.trim() || '';

    return {
      id: item.id,
      title: item.title,
      poster,
      rate,
      year,
    };
  }

  /**
   * 生成唯一的 Origin 头（用于 CORS 代理）
   */
  private getUniqueOrigin(): string {
    const timestamp = Date.now();
    return `https://origin-${timestamp}.example.com`;
  }

  /**
   * 根据数据源构建 API URL
   */
  private buildApiUrl(
    dataSourceKey: DoubanDataSourceKey,
    kind: DoubanKind,
    category: string,
    type: string,
    start: number,
    limit: number
  ): string {
    let baseUrl: string;

    switch (dataSourceKey) {
      case 'cdn_tencent':
        baseUrl = 'https://m.douban.cmliussss.net';
        break;
      case 'cdn_aliyun':
        baseUrl = 'https://m.douban.cmliussss.com';
        break;
      case 'direct':
      default:
        baseUrl = 'https://m.douban.com';
        break;
    }

    const apiUrl = `${baseUrl}/rexxar/api/v2/subject/recent_hot/${kind}?start=${start}&limit=${limit}&category=${category}&type=${type}`;

    // 如果使用 CORS 代理，包装 URL
    if (dataSourceKey === 'cors_proxy') {
      return `https://ciao-cors.is-an.org/${encodeURIComponent(apiUrl)}`;
    }

    return apiUrl;
  }

  /**
   * 获取首页热门电影
   * 
   * @param pageLimit - 每页数量，默认25
   * @param page - 起始页码，默认0
   * @param forceSource - 是否强制从源获取，默认false
   */
  async getHotMovies(params?: {
    pageLimit?: number;
    page?: number;
    forceSource?: boolean;
  }): Promise<ApiResponse<DoubanMovie[]>> {
    const dataSourceKey = await AppDataService.getDoubanDataSourceKey() as DoubanDataSourceKey;
    return this.getCategoryData({
      kind: 'movie',
      category: '热门',
      type: '全部',
      pageLimit: params?.pageLimit ?? 25,
      page: params?.page ?? 0,
      dataSourceKey,
      forceSource: params?.forceSource ?? false,
    });
  }

  /**
   * 获取热门剧集数据
   * 
   * @param pageLimit - 每页数量，默认25
   * @param page - 起始页码，默认0
   * @param forceSource - 是否强制从源获取，默认false
   */
  async getHotTvShows(params?: {
    pageLimit?: number;
    page?: number;
    forceSource?: boolean;
  }): Promise<ApiResponse<DoubanMovie[]>> {
    const dataSourceKey = await AppDataService.getDoubanDataSourceKey() as DoubanDataSourceKey;
    return this.getCategoryData({
      kind: 'tv',
      category: '最近热门',
      type: 'tv',
      pageLimit: params?.pageLimit ?? 25,
      page: params?.page ?? 0,
      dataSourceKey,
      forceSource: params?.forceSource ?? false,
    });
  }

  /**
   * 获取热门综艺数据
   * 
   * @param pageLimit - 每页数量，默认25
   * @param page - 起始页码，默认0
   * @param forceSource - 是否强制从源获取，默认false
   */
  async getHotShows(params?: {
    pageLimit?: number;
    page?: number;
    forceSource?: boolean;
  }): Promise<ApiResponse<DoubanMovie[]>> {
    const dataSourceKey = await AppDataService.getDoubanDataSourceKey() as DoubanDataSourceKey;
    return this.getCategoryData({
      kind: 'tv',
      category: 'show',
      type: 'show',
      pageLimit: params?.pageLimit ?? 25,
      page: params?.page ?? 0,
      dataSourceKey,
      forceSource: params?.forceSource ?? false,
    });
  }

  /**
   * 获取豆瓣分类数据
   * 
   * @param kind - 类型 (movie, tv)
   * @param category - 分类 (热门, tv, show 等)
   * @param type - 子类型 (全部, tv, show 等)
   * @param pageLimit - 每页数量，默认25
   * @param page - 起始页码，默认0
   * @param dataSourceKey - 数据源，默认 'direct'
   */
  async getCategoryData(params: {
    kind: DoubanKind;
    category: string;
    type: string;
    pageLimit?: number;
    page?: number;
    dataSourceKey?: DoubanDataSourceKey;
    forceSource?: boolean;
  }): Promise<ApiResponse<DoubanMovie[]>> {
    const {
      kind,
      category,
      type,
      pageLimit = 25,
      page = 0,
      dataSourceKey = 'direct',
      forceSource = false,
    } = params;

    // 初始化缓存服务
    await this.initCache();

    // 生成缓存键
    const cacheKey = doubanCacheService.generateDoubanCategoryCacheKey({
      kind,
      category,
      type,
      pageLimit,
      page,
    });

    // 如果不强制从源获取，尝试从缓存获取数据
    if (!forceSource) {
      try {
        const cachedData = await doubanCacheService.get<DoubanMovie[]>(
          cacheKey,
          (raw) => {
            if (!Array.isArray(raw)) {
              throw new Error('Invalid cache data format');
            }
            return raw.map((m: any) => ({
              id: m.id?.toString() || '',
              title: m.title?.toString() || '',
              poster: m.poster?.toString() || '',
              rate: m.rate?.toString(),
              year: m.year?.toString() || '',
            }));
          }
        );

        if (cachedData) {
          return ApiResponseHelper.success(cachedData);
        }
      } catch (e) {
        // 缓存读取失败，继续执行网络请求
        console.log('读取缓存失败:', e);
      }
    }

    const start = page * pageLimit;
    const apiUrl = this.buildApiUrl(dataSourceKey, kind, category, type, start, pageLimit);

    try {
      const headers: Record<string, string> = {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
        'Referer': 'https://movie.douban.com/',
        'Accept': 'application/json, text/plain, */*',
      };

      // 如果使用 CORS 代理，添加 Origin 头
      if (dataSourceKey === 'cors_proxy') {
        headers['Origin'] = this.getUniqueOrigin();
      }

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch(apiUrl, {
        method: 'GET',
        headers,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (response.ok) {
        try {
          const data: DoubanResponse = await response.json();
          
          // 验证数据结构
          if (!data || !Array.isArray(data.items)) {
            return ApiResponseHelper.error(`豆瓣数据格式错误: ${JSON.stringify(Object.keys(data || {}))}`);
          }
          
          // 将 items 转换为 DoubanMovie 数组
          const movies = data.items.map((item) => this.parseItemToMovie(item));
          
          // 缓存成功的结果（保存已处理后的 DoubanMovie 列表），缓存时间为 6 小时
          try {
            await doubanCacheService.set(cacheKey, movies, 6 * 60 * 60 * 1000);
          } catch (cacheError) {
            console.log('缓存数据失败:', cacheError);
          }
          
          return ApiResponseHelper.success(movies, response.status);
        } catch (parseError) {
          return ApiResponseHelper.error(
            `豆瓣数据解析失败: ${parseError instanceof Error ? parseError.message : String(parseError)}`
          );
        }
      } else {
        console.log(`获取豆瓣数据失败: ${response.status}`)
        return ApiResponseHelper.error(
          `获取豆瓣数据失败: ${response.status}`,
          response.status
        );
      }
    } catch (error) {
      console.log(`豆瓣数据请求异常: ${error instanceof Error ? error.message : String(error)}`)
      if (error instanceof Error && error.name === 'AbortError') {
        return ApiResponseHelper.error('豆瓣数据请求超时');
      }
      return ApiResponseHelper.error(
        `豆瓣数据请求异常: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }

  /**
   * 获取豆瓣推荐数据（新版筛选逻辑）
   * 
   * @param params - 推荐数据请求参数
   */
  async fetchDoubanRecommends(
    params: DoubanRecommendsParams
  ): Promise<ApiResponse<DoubanMovie[]>> {
    const dataSourceKey = await AppDataService.getDoubanDataSourceKey() as DoubanDataSourceKey;
    const {
      kind,
      category = 'all',
      format = 'all',
      region = 'all',
      year = 'all',
      platform = 'all',
      sort = 'T',
      label = 'all',
      pageLimit = 20,
      page = 0,
    } = params;

    // 初始化缓存服务
    await this.initCache();

    // 生成缓存键
    const cacheKey = doubanCacheService.generateDoubanRecommendsCacheKey({
      kind,
      category,
      format,
      region,
      year,
      platform,
      sort,
      label,
      pageLimit,
      page,
    });

    // 尝试从缓存获取数据（存取均为已处理后的 DoubanMovie 列表）
    try {
      const cachedData = await doubanCacheService.get<DoubanMovie[]>(
        cacheKey,
        (raw) => {
          if (!Array.isArray(raw)) {
            throw new Error('Invalid cache data format');
          }
          return raw.map((m: any) => ({
            id: m.id?.toString() || '',
            title: m.title?.toString() || '',
            poster: m.poster?.toString() || '',
            rate: m.rate?.toString(),
            year: m.year?.toString() || '',
          }));
        }
      );

      if (cachedData) {
        return ApiResponseHelper.success(cachedData);
      }
    } catch (e) {
      // 缓存读取失败，继续执行网络请求
      console.log('读取缓存失败:', e);
    }

    // 处理筛选参数，将 'all' 转换为空字符串
    const categoryParam = category === 'all' ? '' : category;
    const formatParam = format === 'all' ? '' : format;
    const regionParam = region === 'all' ? '' : region;
    const yearParam = year === 'all' ? '' : year;
    const platformParam = platform === 'all' ? '' : platform;
    const labelParam = label === 'all' ? '' : label;
    const sortParam = sort === 'T' ? '' : sort;

    // 构建 selected_categories
    const selectedCategories: Record<string, string> = {
      '类型': categoryParam,
    };
    if (formatParam) {
      selectedCategories['形式'] = formatParam;
    }
    if (regionParam) {
      selectedCategories['地区'] = regionParam;
    }

    // 构建 tags 数组
    const tags: string[] = [];
    if (categoryParam) {
      tags.push(categoryParam);
    }
    if (!categoryParam && formatParam) {
      tags.push(formatParam);
    }
    if (labelParam) {
      tags.push(labelParam);
    }
    if (regionParam) {
      tags.push(regionParam);
    }
    if (yearParam) {
      tags.push(yearParam);
    }
    if (platformParam) {
      tags.push(platformParam);
    }

    // 根据数据源选项构建不同的基础URL
    let baseUrl: string;
    switch (dataSourceKey) {
      case 'cdn_tencent':
        baseUrl = `https://m.douban.cmliussss.net/rexxar/api/v2/${kind}/recommend`;
        break;
      case 'cdn_aliyun':
        baseUrl = `https://m.douban.cmliussss.com/rexxar/api/v2/${kind}/recommend`;
        break;
      case 'direct':
      default:
        baseUrl = `https://m.douban.com/rexxar/api/v2/${kind}/recommend`;
        break;
    }

    // 构建查询参数
    const queryParams: Record<string, string> = {
      refresh: '0',
      start: (page * pageLimit).toString(),
      count: pageLimit.toString(),
      selected_categories: JSON.stringify(selectedCategories),
      uncollect: 'false',
      score_range: '0,10',
      tags: tags.join(','),
    };

    if (sortParam) {
      queryParams.sort = sortParam;
    }

    const urlParams = new URLSearchParams(queryParams);
    let targetUrl = `${baseUrl}?${urlParams.toString()}`;

    // 如果使用 CORS 代理，包装 URL
    if (dataSourceKey === 'cors_proxy') {
      targetUrl = `https://ciao-cors.is-an.org/${encodeURIComponent(targetUrl)}`;
    }

    try {
      const headers: Record<string, string> = {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
        'Referer': 'https://movie.douban.com/',
        'Accept': 'application/json, text/plain, */*',
      };

      // 如果使用 CORS 代理，添加 Origin 头
      if (dataSourceKey === 'cors_proxy') {
        headers['Origin'] = this.getUniqueOrigin();
      }

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch(targetUrl, {
        method: 'GET',
        headers,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (response.ok) {
        try {
          const data: any = await response.json();

          // 验证数据结构
          if (!data || !Array.isArray(data.items)) {
            return ApiResponseHelper.error(
              `豆瓣推荐数据格式错误: ${JSON.stringify(Object.keys(data || {}))}`
            );
          }

          // 过滤并转换数据
          const filteredItems = data.items
            .filter((item: any) => item.type === 'movie' || item.type === 'tv')
            .map((item: any) => this.parseItemToMovie(item));

          // 缓存成功的结果（保存已处理后的 DoubanMovie 列表），缓存时间为 6 小时
          try {
            await doubanCacheService.set(cacheKey, filteredItems, 6 * 60 * 60 * 1000);
          } catch (cacheError) {
            console.log('缓存数据失败:', cacheError);
          }

          return ApiResponseHelper.success(filteredItems, response.status);
        } catch (parseError) {
          return ApiResponseHelper.error(
            `豆瓣推荐数据解析失败: ${parseError instanceof Error ? parseError.message : String(parseError)}`
          );
        }
      } else {
        console.log(`获取豆瓣推荐数据失败: ${response.status}`);
        return ApiResponseHelper.error(
          `获取豆瓣推荐数据失败: ${response.status}`,
          response.status
        );
      }
    } catch (error) {
      console.log(
        `豆瓣推荐数据请求异常: ${error instanceof Error ? error.message : String(error)}`
      );
      if (error instanceof Error && error.name === 'AbortError') {
        return ApiResponseHelper.error('豆瓣推荐数据请求超时');
      }
      return ApiResponseHelper.error(
        `豆瓣推荐数据请求异常: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }
}

// 导出单例
export const doubanService = new DoubanService();
export default doubanService;
