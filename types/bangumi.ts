/**
 * Bangumi 评分数据模型
 */
export interface BangumiRating {
  total: number;
  count: Record<string, number>;
  score: number;
}

/**
 * Bangumi 图片数据模型
 */
export interface BangumiImages {
  large: string;
  common: string;
  medium: string;
  small: string;
  grid: string;
}

/**
 * Bangumi 收藏数据模型
 */
export interface BangumiCollection {
  doing: number;
  onHold: number;
  dropped: number;
  wish: number;
  collect: number;
}

/**
 * Bangumi 星期数据模型
 */
export interface BangumiWeekday {
  en: string;
  cn: string;
  ja: string;
  id: number;
}

/**
 * Bangumi 项目数据模型
 */
export interface BangumiItem {
  id: number;
  url: string;
  type: number;
  name: string;
  nameCn: string | null;
  summary: string;
  airDate: string;
  airWeekday: number;
  rating: BangumiRating;
  rank: number;
  images: BangumiImages;
  collection: BangumiCollection;
}

/**
 * Bangumi 日历响应数据模型
 */
export interface BangumiCalendarResponse {
  weekday: BangumiWeekday;
  items: BangumiItem[];
}
