import { ApiResponse, ApiResponseHelper } from '@/types/api';
import {
  BangumiItem,
  BangumiCalendarResponse,
  BangumiRating,
  BangumiImages,
  BangumiCollection,
  BangumiWeekday,
} from '@/types/bangumi';
import doubanCacheService from './doubanCacheService';

/**
 * Bangumi 服务类
 */
class BangumiService {
  private readonly API_URL = 'https://api.bgm.tv/calendar';
  private readonly USER_AGENT = 'moontechlab/selene/1.0.0 (Android) (http://github.com/moontechlab/selene)';
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
   * 解析 BangumiRating
   */
  private parseRating(json: any): BangumiRating {
    const countData = json?.count || {};
    const safeCount: Record<string, number> = {};
    
    if (typeof countData === 'object') {
      Object.entries(countData).forEach(([key, value]) => {
        safeCount[key] = typeof value === 'number' ? value : parseInt(String(value)) || 0;
      });
    }

    return {
      total: typeof json?.total === 'number' ? json.total : parseInt(String(json?.total || '0')) || 0,
      count: safeCount,
      score: parseFloat(String(json?.score || '0')) || 0,
    };
  }

  /**
   * 解析 BangumiImages
   */
  private parseImages(json: any): BangumiImages {
    return {
      large: String(json?.large || ''),
      common: String(json?.common || ''),
      medium: String(json?.medium || ''),
      small: String(json?.small || ''),
      grid: String(json?.grid || ''),
    };
  }

  /**
   * 解析 BangumiCollection
   */
  private parseCollection(json: any): BangumiCollection {
    return {
      doing: json?.doing || 0,
      onHold: json?.on_hold || 0,
      dropped: json?.dropped || 0,
      wish: json?.wish || 0,
      collect: json?.collect || 0,
    };
  }

  /**
   * 解析 BangumiWeekday
   */
  private parseWeekday(json: any): BangumiWeekday {
    return {
      en: String(json?.en || ''),
      cn: String(json?.cn || ''),
      ja: String(json?.ja || ''),
      id: json?.id || 0,
    };
  }

  /**
   * 解析 BangumiItem
   */
  private parseItem(json: any): BangumiItem {
    return {
      id: json?.id || 0,
      url: String(json?.url || ''),
      type: json?.type || 0,
      name: String(json?.name || ''),
      nameCn: json?.name_cn ? String(json.name_cn) : null,
      summary: String(json?.summary || ''),
      airDate: String(json?.air_date || ''),
      airWeekday: json?.air_weekday || 0,
      rating: this.parseRating(json?.rating),
      rank: json?.rank || 0,
      images: this.parseImages(json?.images),
      collection: this.parseCollection(json?.collection),
    };
  }

  /**
   * 解析 BangumiCalendarResponse
   */
  private parseCalendarResponse(json: any): BangumiCalendarResponse {
    const items = Array.isArray(json?.items) 
      ? json.items.map((item: any) => this.parseItem(item))
      : [];

    return {
      weekday: this.parseWeekday(json?.weekday),
      items,
    };
  }

  /**
   * 获取当天的新番放送（根据当前星期几）
   * @param forceSource - 是否强制从源获取，默认false
   */
  async getTodayCalendar(forceSource: boolean = false): Promise<ApiResponse<BangumiItem[]>> {
    const now = new Date();
    // JavaScript: 0 (Sunday) - 6 (Saturday)
    // Bangumi API: 1 (Monday) - 7 (Sunday)
    const weekday = now.getDay() === 0 ? 7 : now.getDay();
    return this.getCalendarByWeekday(weekday, forceSource);
  }

  /**
   * 获取指定星期的新番放送
   * @param weekday - 星期几 (1..7, Monday..Sunday)
   * @param forceSource - 是否强制从源获取，默认false
   */
  async getCalendarByWeekday(weekday: number, forceSource: boolean = false): Promise<ApiResponse<BangumiItem[]>> {
    await this.initCache();

    // 接口级缓存：缓存原始 API 数组，固定键，不含参数
    const cacheKey = 'bangumi_calendar_raw_v1';

    // 如果不强制从源获取，先尝试读取原始数组缓存
    if (!forceSource) {
      try {
        const cachedRaw = await doubanCacheService.get<any[]>(
          cacheKey,
          (raw) => {
            if (!Array.isArray(raw)) {
              throw new Error('Invalid cache data format');
            }
            return raw;
          }
        );

        if (cachedRaw && cachedRaw.length > 0) {
          // 解析所有星期数据
          const calendar = cachedRaw.map((item) => this.parseCalendarResponse(item));

          // 查找目标星期的数据
          const targetDay = calendar.find((day) => day.weekday.id === weekday);
          const items = targetDay?.items || [];

          return ApiResponseHelper.success(items);
        }
      } catch (_) {
        // 缓存读取失败，继续执行网络请求
      }
    }

    try {
      const headers: Record<string, string> = {
        'User-Agent': this.USER_AGENT,
        'Accept': 'application/json',
      };

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch(this.API_URL, {
        method: 'GET',
        headers,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (response.ok) {
        try {
          const responseData: any[] = await response.json();

          // 缓存原始 API 数组（缓存一天）
          try {
            await doubanCacheService.set(cacheKey, responseData, 24 * 60 * 60 * 1000);
          } catch (cacheError) {
            console.log('缓存 Bangumi 数据失败:', cacheError);
          }

          // 解析所有星期数据
          const calendarData = responseData.map((item) => 
            this.parseCalendarResponse(item)
          );

          // 查找目标星期的数据
          const targetDay = calendarData.find((day) => day.weekday.id === weekday);
          const items = targetDay?.items || [];

          return ApiResponseHelper.success(items, response.status);
        } catch (parseError) {
          return ApiResponseHelper.error(
            `Bangumi 数据解析失败: ${parseError instanceof Error ? parseError.message : String(parseError)}`
          );
        }
      } else {
        return ApiResponseHelper.error(
          `获取 Bangumi 日历失败: ${response.status}`,
          response.status
        );
      }
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        return ApiResponseHelper.error('Bangumi 数据请求超时');
      }
      return ApiResponseHelper.error(
        `Bangumi 数据请求异常: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }
}

// 导出单例
export const bangumiService = new BangumiService();
export default bangumiService;
