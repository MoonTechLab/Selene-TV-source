/**
 * 视频信息数据模型（用于 VideoCard 显示）
 */
export interface VideoInfo {
  id: string;
  source: string;
  title: string;
  sourceName: string;
  year: string;
  cover: string;
  index: number;
  totalEpisodes: number;
  playTime: number;
  totalTime: number;
  saveTime: number;
  searchTitle: string;
  doubanId?: string; // 豆瓣ID，用于豆瓣模式
  bangumiId?: number; // Bangumi ID，用于Bangumi模式
  rate?: string; // 评分
  episodesTitles?: string[]; // 集数标题列表
}

/**
 * 收藏项数据模型
 */
export interface FavoriteItem {
  id: string;
  source: string; // 来源标识
  title: string;
  sourceName: string;
  year: string;
  cover: string;
  totalEpisodes: number;
  saveTime: number;
  origin: string; // 添加origin字段
}

/**
 * 播放记录数据模型
 */
export interface PlayRecord {
  id: string;
  source: string; // 来源标识
  title: string;
  sourceName: string;
  year: string;
  cover: string;
  index: number;
  totalEpisodes: number;
  playTime: number;
  totalTime: number;
  saveTime: number;
  searchTitle: string;
}

/**
 * 将播放记录转换为 VideoInfo
 */
export function playRecordToVideoInfo(playRecord: PlayRecord): VideoInfo {
  // 尝试从 source 中提取 doubanId 或 bangumiId
  let doubanId: string | undefined;
  let bangumiId: number | undefined;

  if (playRecord.source === 'douban') {
    doubanId = playRecord.id;
  } else if (playRecord.source === 'bangumi') {
    bangumiId = parseInt(playRecord.id, 10);
  }

  return {
    id: playRecord.id,
    source: playRecord.source,
    title: playRecord.title,
    sourceName: playRecord.sourceName,
    year: playRecord.year,
    cover: playRecord.cover,
    index: playRecord.index,
    totalEpisodes: playRecord.totalEpisodes,
    playTime: playRecord.playTime,
    totalTime: playRecord.totalTime,
    saveTime: playRecord.saveTime,
    searchTitle: playRecord.searchTitle,
    doubanId,
    bangumiId,
  };
}

/**
 * 将收藏项转换为 VideoInfo
 */
export function favoriteItemToVideoInfo(favoriteItem: FavoriteItem): VideoInfo {
  // 尝试从 source 中提取 doubanId 或 bangumiId
  let doubanId: string | undefined;
  let bangumiId: number | undefined;

  if (favoriteItem.source === 'douban') {
    doubanId = favoriteItem.id;
  } else if (favoriteItem.source === 'bangumi') {
    bangumiId = parseInt(favoriteItem.id, 10);
  }

  return {
    id: favoriteItem.id,
    source: favoriteItem.source,
    title: favoriteItem.title,
    sourceName: favoriteItem.sourceName,
    year: favoriteItem.year,
    cover: favoriteItem.cover,
    index: 0,
    totalEpisodes: favoriteItem.totalEpisodes,
    playTime: 0,
    totalTime: 0,
    saveTime: favoriteItem.saveTime,
    searchTitle: favoriteItem.title,
    doubanId,
    bangumiId,
  };
}

/**
 * 搜索资源模型
 */
export interface SearchResource {
  key: string;
  name: string;
  api: string;
  detail: string;
  from: string;
  disabled: boolean;
}

/**
 * 搜索结果数据模型
 */
export interface SearchResult {
  id: string;
  title: string;
  poster: string;
  episodes: string[];
  episodesTitles: string[];
  source: string;
  sourceName: string;
  class_?: string;
  year: string;
  desc?: string;
  typeName?: string;
  doubanId?: number;
}
