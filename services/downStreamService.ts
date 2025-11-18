import { SearchResource, SearchResult } from '../types/entities';
import ContentFilterService from './contentFilterService';
import LocalSearchCacheService, { CachedPageStatus } from './searchCacheService';

/**
 * 分页搜索结果
 */
export interface SearchPageResult {
  results: SearchResult[];
  pageCount: number;
}

/**
 * 下游搜索服务
 * 用于从第三方API搜索视频资源
 */
class DownstreamService {
  private static readonly _maxSearchPages = 5;
  private static readonly _requestTimeout = 8000; // 8秒超时

  /**
   * 从指定的搜索资源API搜索
   */
  static async searchFromApi(
    resource: SearchResource,
    query: string
  ): Promise<SearchResult[]> {
    try {
      const apiBaseUrl = resource.api;
      const apiUrl = `${apiBaseUrl}?ac=videolist&wd=${encodeURIComponent(query)}`;

      // 搜索第一页
      const firstPageResult = await this._searchPage({
        resource,
        query,
        page: 1,
        url: apiUrl,
      });

      const results = firstPageResult.results;
      const pageCountFromFirst = firstPageResult.pageCount;

      // 计算需要获取的页数
      const pageCount = pageCountFromFirst;
      const pagesToFetch =
        pageCount - 1 < this._maxSearchPages - 1
          ? pageCount - 1
          : this._maxSearchPages - 1;

      // 并发获取其他页面
      if (pagesToFetch > 0) {
        const additionalPagePromises: Promise<SearchResult[]>[] = [];

        for (let page = 2; page <= pagesToFetch + 1; page++) {
          const pageUrl = `${apiBaseUrl}?ac=videolist&wd=${encodeURIComponent(
            query
          )}&pg=${page}`;

          const pagePromise = this._searchPage({
            resource,
            query,
            page,
            url: pageUrl,
          }).then((pageResult) => pageResult.results);

          additionalPagePromises.push(pagePromise);
        }

        const additionalResults = await Promise.all(additionalPagePromises);

        for (const pageResults of additionalResults) {
          if (pageResults.length > 0) {
            results.push(...pageResults);
          }
        }
      }

      // 过滤包含黄色关键词的结果
      const filteredResults = results.filter((result) => {
        return !ContentFilterService.shouldFilter(result.typeName);
      });

      return filteredResults;
    } catch (error) {
      console.error('搜索失败:', error);
      return [];
    }
  }

  /**
   * 清理 HTML 标签
   */
  private static _cleanHtmlTags(text: string): string {
    if (!text) return '';

    const cleanedText = text
      .replace(/<[^>]+>/g, '\n')
      .replace(/\n+/g, '\n')
      .replace(/[ \t]+/g, ' ')
      .replace(/^\n+|\n+$/g, '')
      .trim();

    return this._decodeHtmlEntities(cleanedText);
  }

  /**
   * 解码 HTML 实体
   */
  private static _decodeHtmlEntities(text: string): string {
    return text
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&nbsp;/g, ' ');
  }

  /**
   * 分页搜索
   */
  private static async _searchPage({
    resource,
    query,
    page,
    url,
  }: {
    resource: SearchResource;
    query: string;
    page: number;
    url: string;
  }): Promise<SearchPageResult> {
    // 先查缓存
    const cache = LocalSearchCacheService;
    const cached = cache.getCachedSearchPage(resource.key, query, page);

    if (cached) {
      if (cached.status === CachedPageStatus.OK) {
        return {
          results: cached.data,
          pageCount: cached.pageCount ?? 1,
        };
      } else {
        return { results: [], pageCount: 1 };
      }
    }

    try {
      // 创建带超时的fetch请求
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), this._requestTimeout);

      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
          Accept: 'application/json',
        },
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      // 检查 403 状态码，缓存 forbidden
      if (response.status === 403) {
        cache.setCachedSearchPage(
          resource.key,
          query,
          page,
          CachedPageStatus.FORBIDDEN,
          []
        );
        return { results: [], pageCount: 1 };
      }

      if (!response.ok) {
        return { results: [], pageCount: 1 };
      }

      // 尝试从响应头获取编码
      let charset: string | null = null;
      const contentType = response.headers.get('content-type');
      if (contentType) {
        const charsetMatch = /charset=([^;]+)/.exec(contentType);
        if (charsetMatch) {
          charset = charsetMatch[1].toLowerCase().trim();
        }
      }

      // 获取响应体
      let responseBody: string;
      const arrayBuffer = await response.arrayBuffer();

      // 根据编码解码响应体
      if (charset === 'gbk' || charset === 'gb2312') {
        // GBK/GB2312 编码 - 在浏览器环境中使用 TextDecoder
        try {
          const decoder = new TextDecoder('gbk');
          responseBody = decoder.decode(arrayBuffer);
        } catch (e) {
          // 如果 GBK 解码失败，尝试 UTF-8
          const decoder = new TextDecoder('utf-8');
          responseBody = decoder.decode(arrayBuffer);
        }
      } else {
        // UTF-8 或其他编码
        try {
          const decoder = new TextDecoder('utf-8');
          responseBody = decoder.decode(arrayBuffer);
        } catch (e) {
          // 如果 UTF-8 解码失败，尝试 GBK
          try {
            const decoder = new TextDecoder('gbk');
            responseBody = decoder.decode(arrayBuffer);
          } catch (e2) {
            // 都失败了，使用默认解码
            const decoder = new TextDecoder();
            responseBody = decoder.decode(arrayBuffer);
          }
        }
      }

      const data = JSON.parse(responseBody);

      if (
        !data ||
        !data.list ||
        !Array.isArray(data.list) ||
        data.list.length === 0
      ) {
        return { results: [], pageCount: 1 };
      }

      const list = data.list as any[];

      const allResults = list.map((item) => {
        let episodes: string[] = [];
        let titles: string[] = [];

        if (item.vod_play_url) {
          const vodPlayUrlArray = (item.vod_play_url as string).split('$$$');

          for (const url of vodPlayUrlArray) {
            let matchEpisodes: string[] = [];
            let matchTitles: string[] = [];

            const titleUrlArray = url.split('#');

            for (const titleUrl of titleUrlArray) {
              const episodeTitleUrl = titleUrl.split('$');

              if (
                episodeTitleUrl.length === 2 &&
                episodeTitleUrl[1].endsWith('.m3u8')
              ) {
                matchTitles.push(episodeTitleUrl[0]);
                matchEpisodes.push(episodeTitleUrl[1]);
              }
            }

            if (matchEpisodes.length > episodes.length) {
              episodes = matchEpisodes;
              titles = matchTitles;
            }
          }
        }

        let year = 'unknown';
        if (item.vod_year && item.vod_year !== '') {
          const yearMatch = /\d{4}/.exec(item.vod_year as string);
          if (yearMatch) {
            year = yearMatch[0];
          }
        }

        return {
          id: item.vod_id.toString(),
          title: (item.vod_name as string).trim().replace(/\s+/g, ' '),
          poster: item.vod_pic,
          episodes,
          episodesTitles: titles,
          source: resource.key,
          sourceName: resource.name,
          class_: item.vod_class,
          year,
          desc: this._cleanHtmlTags(item.vod_content ?? ''),
          typeName: item.type_name,
          doubanId: item.vod_douban_id,
        };
      });

      // 过滤出有剧集的结果
      const results: SearchResult[] = allResults
        .filter((result) => result.episodes.length > 0)
        .map((result) => result as SearchResult);

      const pageCount = page === 1 ? (data.pagecount as number | undefined) ?? 1 : 1;

      // 缓存成功的搜索结果
      cache.setCachedSearchPage(
        resource.key,
        query,
        page,
        CachedPageStatus.OK,
        results,
        page === 1 ? pageCount : undefined
      );

      return { results, pageCount };
    } catch (error: any) {
      // 只有超时才缓存 timeout 状态
      if (error.name === 'AbortError') {
        cache.setCachedSearchPage(
          resource.key,
          query,
          page,
          CachedPageStatus.TIMEOUT,
          []
        );
      }

      // 其他异常不缓存，直接返回空结果
      return { results: [], pageCount: 1 };
    }
  }
}

export default DownstreamService;
