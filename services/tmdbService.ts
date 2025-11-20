import AppDataService from './appDataService';
import doubanCacheService from './doubanCacheService';
import { TmdbFindResponse, TmdbImagesResponse } from '../types/tmdb';

class TmdbService {
  private static readonly BASE_URL = 'https://api.themoviedb.org/3';
  private static readonly CACHE_EXPIRATION = 7 * 24 * 60 * 60 * 1000; // 7天

  /**
   * 通过 IMDB ID 查找 TMDB 电影信息
   */
  static async findByImdbId(imdbId: string): Promise<TmdbFindResponse | null> {
    try {
      // 生成缓存键
      const cacheKey = `tmdb_find_${imdbId}`;

      // 尝试从缓存获取
      const cached = await doubanCacheService.get<TmdbFindResponse>(
        cacheKey,
        (data) => data as TmdbFindResponse
      );

      if (cached) {
        return cached;
      }

      // 获取 API Key
      const apiKey = await AppDataService.getTmdbApiKey();
      if (!apiKey) {
        console.warn('TMDB API Key 未配置');
        return null;
      }

      // 请求 API
      const url = `${this.BASE_URL}/find/${imdbId}?api_key=${apiKey}&external_source=imdb_id`;
      const response = await fetch(url);

      if (!response.ok) {
        console.error('TMDB Find API 请求失败:', response.status);
        return null;
      }

      const data: TmdbFindResponse = await response.json();

      // 缓存结果
      await doubanCacheService.set(cacheKey, data, this.CACHE_EXPIRATION);

      return data;
    } catch (error) {
      console.error('查询 TMDB 信息失败:', error);
      return null;
    }
  }

  /**
   * 获取电影图片
   */
  static async getMovieImages(tmdbId: number): Promise<TmdbImagesResponse | null> {
    try {
      // 生成缓存键
      const cacheKey = `tmdb_images_movie_${tmdbId}`;

      // 尝试从缓存获取
      const cached = await doubanCacheService.get<TmdbImagesResponse>(
        cacheKey,
        (data) => data as TmdbImagesResponse
      );

      if (cached) {
        return cached;
      }

      // 获取 API Key
      const apiKey = await AppDataService.getTmdbApiKey();
      if (!apiKey) {
        console.warn('TMDB API Key 未配置');
        return null;
      }

      // 请求 API
      const url = `${this.BASE_URL}/movie/${tmdbId}/images?api_key=${apiKey}`;
      const response = await fetch(url);

      if (!response.ok) {
        console.error('TMDB Images API 请求失败:', response.status);
        return null;
      }

      const data: TmdbImagesResponse = await response.json();

      // 缓存结果
      await doubanCacheService.set(cacheKey, data, this.CACHE_EXPIRATION);

      return data;
    } catch (error) {
      console.error('获取 TMDB 图片失败:', error);
      return null;
    }
  }

  /**
   * 获取电视剧图片
   */
  static async getTVImages(tmdbId: number): Promise<TmdbImagesResponse | null> {
    try {
      // 生成缓存键
      const cacheKey = `tmdb_images_tv_${tmdbId}`;

      // 尝试从缓存获取
      const cached = await doubanCacheService.get<TmdbImagesResponse>(
        cacheKey,
        (data) => data as TmdbImagesResponse
      );

      if (cached) {
        return cached;
      }

      // 获取 API Key
      const apiKey = await AppDataService.getTmdbApiKey();
      if (!apiKey) {
        console.warn('TMDB API Key 未配置');
        return null;
      }

      // 请求 API
      const url = `${this.BASE_URL}/tv/${tmdbId}/images?api_key=${apiKey}`;
      const response = await fetch(url);

      if (!response.ok) {
        console.error('TMDB TV Images API 请求失败:', response.status);
        return null;
      }

      const data: TmdbImagesResponse = await response.json();

      // 缓存结果
      await doubanCacheService.set(cacheKey, data, this.CACHE_EXPIRATION);

      return data;
    } catch (error) {
      console.error('获取 TMDB TV 图片失败:', error);
      return null;
    }
  }
}

export default TmdbService;
