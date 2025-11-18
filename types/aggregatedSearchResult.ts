import { SearchResult, VideoInfo } from './entities';

/**
 * 聚合搜索结果数据模型
 */
export interface AggregatedSearchResult {
  key: string; // 聚合键：标题+年份+类型
  title: string;
  year: string;
  type: 'movie' | 'tv'; // movie 或 tv
  cover: string; // 封面图片
  episodeCounts: Record<string, number>; // 源名称 -> 集数的映射
  doubanIds: Record<string, number>; // 豆瓣ID -> 出现次数的映射
  sourceNames: string[]; // 所有源名称列表
  originalResults: SearchResult[]; // 原始搜索结果列表
  addedTimestamp: number; // 首次添加的时间戳
}

/**
 * 从搜索结果创建聚合结果
 */
export function createAggregatedSearchResult(
  result: SearchResult
): AggregatedSearchResult {
  const type: 'movie' | 'tv' = result.episodes.length > 1 ? 'tv' : 'movie';
  const key = `${result.title}_${result.year}_${type}`;

  const episodeCounts: Record<string, number> = {};
  episodeCounts[result.sourceName] = result.episodes.length;

  const doubanIds: Record<string, number> = {};
  if (result.doubanId && result.doubanId > 0) {
    doubanIds[result.doubanId.toString()] = 1;
  }

  return {
    key,
    title: result.title,
    year: result.year,
    type,
    cover: result.poster,
    episodeCounts,
    doubanIds,
    sourceNames: [result.sourceName],
    originalResults: [result],
    addedTimestamp: Date.now(),
  };
}

/**
 * 添加新的搜索结果到聚合结果中
 */
export function addResultToAggregated(
  aggregated: AggregatedSearchResult,
  result: SearchResult
): AggregatedSearchResult {
  const newEpisodeCounts = { ...aggregated.episodeCounts };
  newEpisodeCounts[result.sourceName] = result.episodes.length;

  const newDoubanIds = { ...aggregated.doubanIds };
  if (result.doubanId && result.doubanId > 0) {
    const doubanIdStr = result.doubanId.toString();
    newDoubanIds[doubanIdStr] = (newDoubanIds[doubanIdStr] || 0) + 1;
  }

  const newSourceNames = [...aggregated.sourceNames];
  if (!newSourceNames.includes(result.sourceName)) {
    newSourceNames.push(result.sourceName);
  }

  const newOriginalResults = [...aggregated.originalResults, result];

  return {
    ...aggregated,
    episodeCounts: newEpisodeCounts,
    doubanIds: newDoubanIds,
    sourceNames: newSourceNames,
    originalResults: newOriginalResults,
  };
}

/**
 * 获取最常见的集数
 */
export function getMostCommonEpisodeCount(
  aggregated: AggregatedSearchResult
): number {
  const episodeCounts = Object.values(aggregated.episodeCounts);
  if (episodeCounts.length === 0) return 0;

  // 统计每个集数的出现次数
  const countFrequency: Record<number, number> = {};
  for (const count of episodeCounts) {
    countFrequency[count] = (countFrequency[count] || 0) + 1;
  }

  // 找出出现次数最多的集数
  let mostCommon = episodeCounts[0];
  let maxFrequency = 0;

  Object.entries(countFrequency).forEach(([count, frequency]) => {
    if (frequency > maxFrequency) {
      maxFrequency = frequency;
      mostCommon = parseInt(count, 10);
    }
  });

  return mostCommon;
}

/**
 * 获取最常见的豆瓣ID
 */
export function getMostCommonDoubanId(
  aggregated: AggregatedSearchResult
): string | null {
  if (Object.keys(aggregated.doubanIds).length === 0) return null;

  let mostCommon: string | null = null;
  let maxCount = 0;

  Object.entries(aggregated.doubanIds).forEach(([id, count]) => {
    if (count > maxCount) {
      maxCount = count;
      mostCommon = id;
    }
  });

  return mostCommon;
}

/**
 * 转换为VideoInfo用于显示
 */
export function aggregatedSearchResultToVideoInfo(
  aggregated: AggregatedSearchResult
): VideoInfo {
  return {
    id: aggregated.key, // 使用聚合键作为ID
    source: 'aggregated', // 标记为聚合来源
    title: aggregated.title,
    sourceName: aggregated.sourceNames.join(', '), // 显示所有源名称
    year: aggregated.year,
    cover: aggregated.cover,
    index: 1,
    totalEpisodes: getMostCommonEpisodeCount(aggregated),
    playTime: 0,
    totalTime: 0,
    saveTime: aggregated.addedTimestamp,
    searchTitle: aggregated.title,
    doubanId: getMostCommonDoubanId(aggregated) || undefined,
  };
}

/**
 * 生成聚合键
 */
export function generateAggregationKey(
  title: string,
  year: string,
  episodeCount: number
): string {
  const type = episodeCount > 1 ? 'tv' : 'movie';
  return `${title}_${year}_${type}`;
}
