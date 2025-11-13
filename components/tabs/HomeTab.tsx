import { StyleSheet, View, Text } from 'react-native';
import { useRef, useEffect, useState } from 'react';
import RecommendSection from '../RecommendSection';
import doubanService from '@/services/doubanService';
import bangumiService from '@/services/bangumiService';
import { DoubanMovie } from '@/types/douban';
import { BangumiItem } from '@/types/bangumi';

const mockVideos = (prefix: string, count: number = 10) => 
  Array.from({ length: count }, (_, i) => ({
    id: `${prefix}-${i + 1}`,
    title: i % 3 === 1 
      ? `这是一个很长的视频标题需要滚动展示的内容 ${i + 1}` 
      : `示例视频标题 ${i + 1}`,
    coverUrl: 'https://via.placeholder.com/130x195',
  }));

export default function HomeTab() {
  const seriesTitleRef = useRef<any>(null);
  const animeTitleRef = useRef<any>(null);
  const varietyTitleRef = useRef<any>(null);

  const [hotMovies, setHotMovies] = useState<DoubanMovie[]>([]);
  const [hotTvShows, setHotTvShows] = useState<DoubanMovie[]>([]);
  const [hotShows, setHotShows] = useState<DoubanMovie[]>([]);
  const [todayBangumi, setTodayBangumi] = useState<BangumiItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadAllData();
  }, []);

  const loadAllData = async () => {
    setLoading(true);
    setError(null);

    try {
      // 并行加载所有数据
      const [moviesRes, tvShowsRes, showsRes, bangumiRes] = await Promise.all([
        doubanService.getHotMovies({ pageLimit: 25, page: 0 }),
        doubanService.getHotTvShows({ pageLimit: 25, page: 0 }),
        doubanService.getHotShows({ pageLimit: 25, page: 0 }),
        bangumiService.getTodayCalendar(),
      ]);

      if (moviesRes.success && moviesRes.data) {
        setHotMovies(moviesRes.data);
      }

      if (tvShowsRes.success && tvShowsRes.data) {
        setHotTvShows(tvShowsRes.data);
      }

      if (showsRes.success && showsRes.data) {
        setHotShows(showsRes.data);
      }

      if (bangumiRes.success && bangumiRes.data) {
        setTodayBangumi(bangumiRes.data);
      }
    } catch (err) {
      // setError('加载异常');
    }

    setLoading(false);
  };

  // 将豆瓣数据转换为 RecommendSection 需要的格式
  const convertToVideos = (items: DoubanMovie[]) =>
    (items || []).map((item) => ({
      id: item.id,
      title: item.title,
      coverUrl: item.poster,
    }));

  // 将 Bangumi 数据转换为 RecommendSection 需要的格式
  const convertBangumiToVideos = (items: BangumiItem[]) =>
    (items || []).map((item) => ({
      id: String(item.id),
      title: item.nameCn || item.name,
      coverUrl: item.images.large || item.images.common || item.images.medium || item.images.small || item.images.grid,
    }));

  const hotMovieVideos = convertToVideos(hotMovies);
  const hotTvShowVideos = convertToVideos(hotTvShows);
  const hotShowVideos = convertToVideos(hotShows);
  const todayBangumiVideos = convertBangumiToVideos(todayBangumi);

  return (
    <View style={styles.container}>
      {error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>加载失败: {error}</Text>
        </View>
      )}
      <RecommendSection 
        title="热门电影" 
        videos={hotMovieVideos}
        loading={loading}
        nextSectionTitleRef={seriesTitleRef}
      />
      <RecommendSection 
        title="热门剧集" 
        videos={hotTvShowVideos}
        loading={loading}
        titleRef={seriesTitleRef}
        nextSectionTitleRef={animeTitleRef}
      />
      <RecommendSection 
        title="新番放送" 
        videos={todayBangumiVideos}
        loading={loading}
        titleRef={animeTitleRef}
        nextSectionTitleRef={varietyTitleRef}
      />
      <RecommendSection
        title="热门综艺" 
        videos={hotShowVideos}
        loading={loading}
        titleRef={varietyTitleRef}
        isLast={true}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingTop: 5,
  },
  errorContainer: {
    padding: 20,
    alignItems: 'center',
  },
  errorText: {
    color: '#ff6b6b',
    fontSize: 14,
  },
});
