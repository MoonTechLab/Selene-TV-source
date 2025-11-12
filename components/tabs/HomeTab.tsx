import { StyleSheet, View, Text } from 'react-native';
import { useRef, useEffect, useState } from 'react';
import RecommendSection from '../RecommendSection';
import doubanService from '@/services/doubanService';
import { DoubanMovie } from '@/types/douban';

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
      const [moviesRes, tvShowsRes, showsRes] = await Promise.all([
        doubanService.getHotMovies({ pageLimit: 25, page: 0 }),
        doubanService.getHotTvShows({ pageLimit: 25, page: 0 }),
        doubanService.getHotShows({ pageLimit: 25, page: 0 }),
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

      // 如果所有请求都失败，显示错误
      if (!moviesRes.success && !tvShowsRes.success && !showsRes.success) {
        setError('加载失败');
      }
    } catch (err) {
      setError('加载异常');
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

  const hotMovieVideos = convertToVideos(hotMovies);
  const hotTvShowVideos = convertToVideos(hotTvShows);
  const hotShowVideos = convertToVideos(hotShows);

  return (
    <View style={styles.container}>
      {error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>加载失败: {error}</Text>
        </View>
      )}
      <RecommendSection 
        title="热门电影" 
        videos={loading || hotMovieVideos.length === 0 ? mockVideos('movie', 12) : hotMovieVideos}
        nextSectionTitleRef={seriesTitleRef}
      />
      <RecommendSection 
        title="热门剧集" 
        videos={loading || hotTvShowVideos.length === 0 ? mockVideos('series', 12) : hotTvShowVideos}
        titleRef={seriesTitleRef}
        nextSectionTitleRef={animeTitleRef}
      />
      <RecommendSection 
        title="新番放送" 
        videos={mockVideos('anime', 12)}
        titleRef={animeTitleRef}
        nextSectionTitleRef={varietyTitleRef}
      />
      <RecommendSection 
        title="热门综艺" 
        videos={loading || hotShowVideos.length === 0 ? mockVideos('variety', 12) : hotShowVideos}
        titleRef={varietyTitleRef}
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
