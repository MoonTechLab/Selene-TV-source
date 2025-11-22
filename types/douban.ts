import { VideoInfo } from './entities';

// 豆瓣电影数据类型
export interface DoubanMovie {
  id: string;
  title: string;
  poster: string;
  rate?: string;
  year: string;
}

/**
 * 将豆瓣电影数据转换为 VideoInfo
 */
export function doubanMovieToVideoInfo(movie: DoubanMovie): VideoInfo {
  return {
    id: movie.id,
    source: 'douban',
    title: movie.title,
    sourceName: '豆瓣',
    year: movie.year || '',
    cover: movie.poster,
    index: 1,
    totalEpisodes: 1,
    playTime: 0,
    totalTime: 0,
    saveTime: Date.now(),
    searchTitle: movie.title,
    doubanId: movie.id,
    rate: movie.rate,
  };
}

// 豆瓣 API 响应类型
export interface DoubanResponse {
  category: string;
  type: string;
  total: number;
  items: DoubanItem[];
  tags?: any[];
  recommend_tags?: any[];
}

// 豆瓣 API 返回的原始数据结构
export interface DoubanItem {
  id: string;
  title: string;
  type: string;
  card_subtitle?: string;
  episodes_info?: string;
  is_new?: boolean;
  uri?: string;
  pic?: {
    large?: string;
    normal?: string;
    small?: string;
  };
  rating?: {
    count?: number;
    max?: number;
    star_count?: number;
    value?: number;
  };
}

// 数据源类型
export type DoubanDataSourceKey = 'direct' | 'cdn_tencent' | 'cdn_aliyun' | 'cors_proxy';

// 内容类型
export type DoubanKind = 'movie' | 'tv';

// 豆瓣推荐数据请求参数
export interface DoubanRecommendsParams {
  kind: DoubanKind;
  category?: string;
  format?: string;
  region?: string;
  year?: string;
  platform?: string;
  sort?: string;
  label?: string;
  pageLimit?: number;
  page?: number;
  dataSourceKey?: DoubanDataSourceKey;
}

// 豆瓣推荐项目
export interface DoubanRecommendItem {
  id: string;
  title: string;
  poster: string;
  rate?: string;
}

// 豆瓣电影详情
export interface DoubanMovieDetails {
  id: string;
  title: string;
  poster: string;
  rate?: string;
  year: string;
  summary?: string;
  genres: string[];
  directors: string[];
  screenwriters: string[];
  actors: string[];
  duration?: string;
  countries: string[];
  languages: string[];
  releaseDate?: string;
  originalTitle?: string;
  imdbId?: string;
  totalEpisodes?: number;
  recommends: DoubanRecommendItem[];
  backdrop?: string;
  logo?: string;
}
