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
 * 获取最佳图片 URL
 */
export function getBestImageUrl(images: BangumiImages): string {
  return images.large || images.common || images.medium || images.small || images.grid;
}

/**
 * 将 Bangumi 项目数据转换为 VideoInfo
 */
export function bangumiItemToVideoInfo(item: BangumiItem): VideoInfo {
  const title = item.nameCn || item.name;
  const year = item.airDate ? item.airDate.split('-')[0] : '';
  const rate = item.rating.score > 0 ? item.rating.score.toFixed(1) : undefined;

  return {
    id: item.id.toString(),
    source: 'bangumi',
    title,
    sourceName: 'Bangumi',
    year,
    cover: getBestImageUrl(item.images),
    index: 1,
    totalEpisodes: 1,
    playTime: 0,
    totalTime: 0,
    saveTime: Date.now(),
    searchTitle: title,
    bangumiId: item.id,
    rate,
  };
}

import { VideoInfo } from './entities';

/**
 * Bangumi 日历响应数据模型
 */
export interface BangumiCalendarResponse {
  weekday: BangumiWeekday;
  items: BangumiItem[];
}

/**
 * Bangumi 详情数据模型
 */
export interface BangumiDetails {
  id: number;
  type: number;
  name: string;
  nameCn: string | null;
  summary: string;
  nsfw: boolean;
  locked: boolean;
  date: string | null;
  platform: string | null;
  images: BangumiImages;
  infobox: string[];
  volumes: number;
  eps: number;
  totalEpisodes: number;
  rating: BangumiRating;
  collection: BangumiCollection;
  tags: string[];
  metaTags: string[];
  series: boolean;
}
