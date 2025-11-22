import { ApiResponse, ApiResponseHelper } from '@/types/api';
import {
  DoubanMovie,
  DoubanResponse,
  DoubanItem,
  DoubanDataSourceKey,
  DoubanKind,
  DoubanRecommendsParams,
  DoubanMovieDetails,
  DoubanRecommendItem,
} from '@/types/douban';
import doubanCacheService from './doubanCacheService';
import AppDataService from './appDataService';
import TmdbService from './tmdbService';

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

  /**
   * 获取豆瓣详情数据
   * 
   * @param doubanId - 豆瓣ID
   */
  async getDoubanDetails(doubanId: string): Promise<ApiResponse<DoubanMovieDetails>> {
    // 初始化缓存服务
    await this.initCache();

    // 生成缓存键
    const cacheKey = doubanCacheService.generateDoubanDetailsCacheKey({
      doubanId,
    });

    // 尝试从缓存获取数据
    try {
      const cachedData = await doubanCacheService.get<DoubanMovieDetails>(
        cacheKey,
        (raw) => {
          const map = raw as any;

          // 处理推荐列表
          let recommends: DoubanRecommendItem[] = [];
          if (map.recommends && Array.isArray(map.recommends)) {
            recommends = map.recommends.map((r: any) => ({
              id: r.id?.toString() || '',
              title: r.title?.toString() || '',
              poster: r.poster?.toString() || '',
              rate: r.rate?.toString(),
            }));
          }

          return {
            id: map.id?.toString() || '',
            title: map.title?.toString() || '',
            poster: map.poster?.toString() || '',
            rate: map.rate?.toString(),
            year: map.year?.toString() || '',
            summary: map.summary?.toString(),
            genres: (Array.isArray(map.genres) ? map.genres : [])
              .map((g: any) => g.toString()),
            directors: (Array.isArray(map.directors) ? map.directors : [])
              .map((d: any) => d.toString()),
            screenwriters: (Array.isArray(map.screenwriters) ? map.screenwriters : [])
              .map((s: any) => s.toString()),
            actors: (Array.isArray(map.actors) ? map.actors : [])
              .map((a: any) => a.toString()),
            duration: map.duration?.toString(),
            countries: (Array.isArray(map.countries) ? map.countries : [])
              .map((c: any) => c.toString()),
            languages: (Array.isArray(map.languages) ? map.languages : [])
              .map((l: any) => l.toString()),
            releaseDate: map.releaseDate?.toString(),
            originalTitle: map.originalTitle?.toString(),
            imdbId: map.imdbId?.toString(),
            recommends: recommends,
            totalEpisodes: map.totalEpisodes,
            backdrop: map.backdrop?.toString(),
            logo: map.logo?.toString(),
          };
        }
      );

      if (cachedData) {
        return ApiResponseHelper.success(cachedData);
      }
    } catch (e) {
      // 缓存读取失败，继续执行网络请求
      console.log('读取豆瓣详情缓存失败:', e);
    }

    // 获取用户存储的豆瓣数据源选项
    const dataSourceKey = await AppDataService.getDoubanDataSourceKey() as DoubanDataSourceKey;

    // 根据数据源选项构建不同的基础URL
    let apiUrl: string;
    switch (dataSourceKey) {
      case 'cdn_tencent':
        apiUrl = `https://movie.douban.cmliussss.net/subject/${doubanId}`;
        break;
      case 'cdn_aliyun':
        apiUrl = `https://movie.douban.cmliussss.com/subject/${doubanId}`;
        break;
      case 'direct':
      default:
        apiUrl = `https://movie.douban.com/subject/${doubanId}`;
        break;
    }

    if (dataSourceKey === 'cors_proxy') {
      apiUrl = `https://ciao-cors.is-an.org/${encodeURIComponent(apiUrl)}`;
    }

    try {
      const headers: Record<string, string> = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
        'Referer': 'https://movie.douban.com/',
        'Accept': 'application/json, text/plain, */*',
      };

      // 如果使用 cors_proxy，添加 Origin 头
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
          const html = await response.text();
          // 解析HTML响应
          const details = this.parseDoubanHtmlDetails(html, doubanId);

          // 获取 TMDB 图片
          await this.fetchTmdbImages(details);

          // 缓存成功的结果，缓存时间为3天
          try {
            await doubanCacheService.set(
              cacheKey,
              details,
              3 * 24 * 60 * 60 * 1000,
            );
          } catch (cacheError) {
            console.log('缓存豆瓣详情数据失败:', cacheError);
          }

          return ApiResponseHelper.success(details, response.status);
        } catch (parseError) {
          return ApiResponseHelper.error(`豆瓣详情数据解析失败: ${parseError instanceof Error ? parseError.message : String(parseError)}`);
        }
      } else {
        return ApiResponseHelper.error(
          `获取豆瓣详情数据失败: ${response.status}`,
          response.status,
        );
      }
    } catch (e) {
      if (e instanceof Error && e.name === 'AbortError') {
        return ApiResponseHelper.error('豆瓣详情数据请求超时');
      }
      return ApiResponseHelper.error(`豆瓣详情数据请求异常: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * 解析豆瓣HTML详情页面
   */
  private parseDoubanHtmlDetails(html: string, id: string): DoubanMovieDetails {
    try {
      // 提取基本信息 - 标题
      const titleRegex = /<h1[^>]*>[\s\S]*?<span[^>]*property="v:itemreviewed"[^>]*>([^<]+)<\/span>/;
      const titleMatch = html.match(titleRegex);
      const title = titleMatch?.[1]?.trim() || '';

      // 提取海报
      const posterRegex = /<a[^>]*class="nbgnbg"[^>]*>[\s\S]*?<img[^>]*src="([^"]+)"/;
      const posterMatch = html.match(posterRegex);
      const poster = posterMatch?.[1] || '';

      // 提取评分
      const ratingRegex = /<strong[^>]*class="ll rating_num"[^>]*property="v:average">([^<]+)<\/strong>/;
      const ratingMatch = html.match(ratingRegex);
      const rate = ratingMatch?.[1];

      // 提取年份
      const yearRegex = /<span[^>]*class="year">[(]([^)]+)[)]<\/span>/;
      const yearMatch = html.match(yearRegex);
      const year = yearMatch?.[1] || '';

      // 提取导演
      let directors: string[] = [];
      const directorRegex = /<span class=["']pl["']>导演<\/span>:\s*<span class=["']attrs["']>(.*?)<\/span>/;
      const directorMatch = html.match(directorRegex);
      if (directorMatch) {
        const directorLinks = directorMatch[1].match(/<a[^>]*>([^<]+)<\/a>/g);
        if (directorLinks) {
          directors = directorLinks
            .map(link => link.replace(/<[^>]+>/g, '').trim())
            .filter(name => name.length > 0);
        }
      }

      // 提取编剧
      let screenwriters: string[] = [];
      const writerRegex = /<span class=["']pl["']>编剧<\/span>:\s*<span class=["']attrs["']>(.*?)<\/span>/;
      const writerMatch = html.match(writerRegex);
      if (writerMatch) {
        const writerLinks = writerMatch[1].match(/<a[^>]*>([^<]+)<\/a>/g);
        if (writerLinks) {
          screenwriters = writerLinks
            .map(link => link.replace(/<[^>]+>/g, '').trim())
            .filter(name => name.length > 0);
        }
      }

      // 提取主演
      let actors: string[] = [];
      const castRegex = /<span class=["']pl["']>主演<\/span>:\s*<span class=["']attrs["']>(.*?)<\/span>/;
      const castMatch = html.match(castRegex);
      if (castMatch) {
        const castLinks = castMatch[1].match(/<a[^>]*>([^<]+)<\/a>/g);
        if (castLinks) {
          actors = castLinks
            .map(link => link.replace(/<[^>]+>/g, '').trim())
            .filter(name => name.length > 0);
        }
      }

      // 提取类型
      const genreRegex = /<span[^>]*property="v:genre">([^<]+)<\/span>/g;
      const genreMatches = [...html.matchAll(genreRegex)];
      const genres = genreMatches.map(match => match[1] || '').filter(genre => genre.length > 0);

      // 提取制片国家/地区
      const countryRegex = /<span[^>]*class="pl">制片国家\/地区:<\/span>([^<]+)/;
      const countryMatch = html.match(countryRegex);
      const countries = countryMatch?.[1]?.trim().split('/').map(c => c.trim()).filter(c => c.length > 0) || [];

      // 提取语言
      const languageRegex = /<span[^>]*class="pl">语言:<\/span>([^<]+)/;
      const languageMatch = html.match(languageRegex);
      const languages = languageMatch?.[1]?.trim().split('/').map(l => l.trim()).filter(l => l.length > 0) || [];

      // 提取首播/上映日期
      let releaseDate: string | undefined;
      const firstAiredRegex = /<span class="pl">首播:<\/span>\s*<span[^>]*property="v:initialReleaseDate"[^>]*content="([^"]*)"[^>]*>([^<]*)<\/span>/;
      const firstAiredMatch = html.match(firstAiredRegex);
      if (firstAiredMatch) {
        releaseDate = firstAiredMatch[1];
      } else {
        const releaseDateRegex = /<span class="pl">上映日期:<\/span>\s*<span[^>]*property="v:initialReleaseDate"[^>]*content="([^"]*)"[^>]*>([^<]*)<\/span>/;
        const releaseDateMatch = html.match(releaseDateRegex);
        if (releaseDateMatch) {
          releaseDate = releaseDateMatch[1];
        }
      }

      // 提取集数（仅剧集有）
      let episodes: number | undefined;
      const episodesRegex = /<span[^>]*class="pl">集数:<\/span>([^<]+)/;
      const episodesMatch = html.match(episodesRegex);
      if (episodesMatch) {
        const episodesStr = episodesMatch[1]?.trim();
        if (episodesStr) {
          episodes = parseInt(episodesStr, 10);
          if (isNaN(episodes)) episodes = undefined;
        }
      }

      // 提取时长 - 支持电影和剧集
      let episodeLength: number | undefined;
      let movieDuration: number | undefined;

      // 先尝试提取剧集的单集片长
      const singleEpisodeDurationRegex = /<span[^>]*class="pl">单集片长:<\/span>([^<]+)/;
      const singleEpisodeDurationMatch = html.match(singleEpisodeDurationRegex);
      if (singleEpisodeDurationMatch) {
        const lenStr = singleEpisodeDurationMatch[1]?.trim();
        if (lenStr) {
          episodeLength = parseInt(lenStr, 10);
        }
      } else {
        // 如果没有单集片长，尝试提取电影的总片长
        const movieDurationRegex = /<span[^>]*class="pl">片长:<\/span>([^<]+)/;
        const movieDurationMatch = html.match(movieDurationRegex);
        if (movieDurationMatch) {
          const durStr = movieDurationMatch[1]?.trim();
          if (durStr) {
            movieDuration = parseInt(durStr, 10);
          }
        }
      }

      // 为了保持与现有代码的兼容性，将时长转换为字符串
      let duration: string | undefined;
      if (episodeLength) {
        duration = `${episodeLength}分钟`;
      } else if (movieDuration) {
        duration = `${movieDuration}分钟`;
      }

      // 提取剧情简介 - 两个正则都匹配，选择内容更长的
      let summary: string | undefined;

      // 使用 [\s\S] 来匹配包括换行符在内的所有字符
      const summaryRegex1 = /<span[^>]*class="all hidden">([\s\S]*?)<\/span>/;
      const summaryMatch1 = html.match(summaryRegex1);
      let summary1: string | undefined;
      if (summaryMatch1) {
        summary1 = summaryMatch1[1]
          .replace(/<br\s*\/?>/gi, '|||LINEBREAK|||') // 先用特殊标记替换<br>
          .replace(/<[^>]*>/g, '') // 移除所有HTML标签
          .replace(/\s+/g, ' ') // 去除重复空格
          .replace(/\|\|\|LINEBREAK\|\|\|/g, '\n') // 将特殊标记恢复为换行符
          .trim()
          .split('\n')
          .join('\n');
      }

      const summaryRegex2 = /<span[^>]*property="v:summary"[^>]*>([\s\S]*?)<\/span>/;
      const summaryMatch2 = html.match(summaryRegex2);
      let summary2: string | undefined;
      if (summaryMatch2) {
        summary2 = summaryMatch2[1]
          .replace(/<br\s*\/?>/gi, '|||LINEBREAK|||')
          .replace(/<[^>]*>/g, '')
          .replace(/\s+/g, ' ')
          .replace(/\|\|\|LINEBREAK\|\|\|/g, '\n')
          .trim()
          .split('\n')
          .join('\n');
      }

      // 选择内容更长的简介
      if (summary1 && summary2) {
        summary = summary1.length >= summary2.length ? summary1 : summary2;
      } else if (summary1) {
        summary = summary1;
      } else if (summary2) {
        summary = summary2;
      }

      // 提取 IMDb ID
      let imdbId: string | undefined;
      const imdbRegex = /<span[^>]*class="pl">IMDb:<\/span>([^<]+)/;
      const imdbMatch = html.match(imdbRegex);
      if (imdbMatch) {
        imdbId = imdbMatch[1]?.trim();
      }

      // 提取推荐区域
      const recommends: DoubanRecommendItem[] = [];
      try {
        // 查找推荐区域
        const recommendationsRegex = /<div[^>]*id="recommendations"[^>]*>([\s\S]*?)<\/div>/;
        const recommendationsMatch = html.match(recommendationsRegex);

        if (recommendationsMatch) {
          const recommendationsContent = recommendationsMatch[1] || '';

          // 提取所有推荐项目
          const dlRegex = /<dl>([\s\S]*?)<\/dl>/g;
          const dlMatches = [...recommendationsContent.matchAll(dlRegex)];

          for (const dlMatch of dlMatches) {
            const dlContent = dlMatch[1] || '';

            // 提取链接和海报
            const linkRegex = /<a[^>]*href="https:\/\/movie\.douban\.com\/subject\/(\d+)\/[^"]*"[^>]*>/;
            const linkMatch = dlContent.match(linkRegex);

            // 提取海报图片
            const imgRegex = /<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"/;
            const imgMatch = dlContent.match(imgRegex);

            // 提取评分
            const rateRegex = /<span[^>]*class="subject-rate"[^>]*>([^<]*)<\/span>/;
            const rateMatch = dlContent.match(rateRegex);

            if (linkMatch && imgMatch) {
              const recommendId = linkMatch[1] || '';
              const posterUrl = imgMatch[1] || '';
              const title = imgMatch[2] || '';
              const recommendRate = rateMatch?.[1]?.trim();

              // 过滤掉空的评分
              const rate = recommendRate && recommendRate.length > 0 ? recommendRate : undefined;

              if (recommendId && title && posterUrl) {
                recommends.push({
                  id: recommendId,
                  title: title,
                  poster: posterUrl,
                  rate: rate,
                });
              }
            }
          }
        }
      } catch (e) {
        // 推荐区域解析失败，继续执行
        console.log('解析推荐区域失败:', e);
      }

      return {
        id,
        title,
        poster,
        rate,
        year,
        summary,
        genres,
        directors,
        screenwriters,
        actors,
        duration,
        countries,
        languages,
        releaseDate,
        originalTitle: undefined, // HTML页面中暂未找到原始标题的提取逻辑
        imdbId,
        recommends,
        totalEpisodes: episodes,
      };
    } catch (e) {
      // 如果解析失败，返回基本信息
      return {
        id,
        title: '解析失败',
        poster: '',
        year: '',
        genres: [],
        directors: [],
        screenwriters: [],
        actors: [],
        countries: [],
        languages: [],
        recommends: [],
      };
    }
  }

  /**
   * 获取 TMDB 图片（backdrop 和 logo）
   */
  private async fetchTmdbImages(details: DoubanMovieDetails): Promise<void> {
    if (!details.imdbId) {
      return;
    }

    const tmdbApiKey = await AppDataService.getTmdbApiKey();
    if (!tmdbApiKey) {
      return;
    }

    try {
      // 通过 IMDB ID 查找 TMDB ID
      const findResponse = await TmdbService.findByImdbId(details.imdbId);
      if (!findResponse) {
        console.log('TMDB 查询失败');
        return;
      }

      // 优先从 movie_results 获取，如果为空则从 tv_results 获取
      let tmdbId: number | null = null;
      let mediaType: 'movie' | 'tv' = 'movie';
      
      if (findResponse.movie_results.length > 0) {
        tmdbId = findResponse.movie_results[0].id;
        mediaType = 'movie';
      } else if (findResponse.tv_results.length > 0) {
        tmdbId = findResponse.tv_results[0].id;
        mediaType = 'tv';
      }

      if (!tmdbId) {
        console.log('未找到对应的 TMDB 内容');
        return;
      }

      console.log(`找到 TMDB ${mediaType} ID: ${tmdbId}`);
      
      // 根据媒体类型获取图片信息
      const imagesResponse = mediaType === 'movie' 
        ? await TmdbService.getMovieImages(tmdbId)
        : await TmdbService.getTVImages(tmdbId);
        
      if (!imagesResponse) {
        console.log('未获取到 TMDB 图片');
        return;
      }

      // 获取第一张 backdrop
      const backdropPath = imagesResponse.backdrops[0]?.file_path;
      
      // 获取 logo：只选择 png、jpg 或 webp 格式的，从这些 logo 中优先选择中文的
      let logoPath: string | null = null;
      if (imagesResponse.logos.length > 0) {
        // 过滤出 png、jpg 或 webp 后缀的 logo
        const validLogos = imagesResponse.logos.filter(logo => {
          const filePath = logo.file_path.toLowerCase();
          return filePath.endsWith('.png') || filePath.endsWith('.jpg') || filePath.endsWith('.jpeg') || filePath.endsWith('.webp');
        });
        
        if (validLogos.length > 0) {
          // 从有效的 logo 中优先选择中文的
          const cnLogo = validLogos.find(logo => logo.iso_3166_1 === 'CN');
          logoPath = cnLogo ? cnLogo.file_path : validLogos[0].file_path;
        }
      }

      if (!backdropPath && !logoPath) {
        return;
      }

      // 构建完整的图片 URL
      const backdropUrl = backdropPath ? `https://image.tmdb.org/t/p/original${backdropPath}` : undefined;
      // 如果 backdrop 没有展示，则也不要展示 logo
      const logoUrl = (logoPath && backdropUrl) ? `https://image.tmdb.org/t/p/original${logoPath}` : undefined;

      console.log('backdrop: ' + backdropUrl);
      console.log('logo: ' + logoUrl);

      // 设置 backdrop 和 logo
      details.backdrop = backdropUrl;
      details.logo = logoUrl;

    } catch (error) {
      console.error('获取 TMDB 图片失败:', error);
    }
  }
}

// 导出单例
export const doubanService = new DoubanService();
export default doubanService;
