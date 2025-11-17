import { StyleSheet, View, Text, findNodeHandle } from 'react-native';
import { useRef, useEffect, useState } from 'react';
import RecommendSection from '../RecommendSection';
import PlayRecordSection from '../PlayRecordSection';
import FavoriteSection from '../FavoriteSection';
import doubanService from '@/services/doubanService';
import bangumiService from '@/services/bangumiService';
import userDataCacheService from '@/services/userDataCacheService';
import { DoubanMovie, doubanMovieToVideoInfo } from '@/types/douban';
import { BangumiItem, bangumiItemToVideoInfo } from '@/types/bangumi';
import { PlayRecord, playRecordToVideoInfo, FavoriteItem, favoriteItemToVideoInfo, VideoInfo } from '@/types/entities';

interface HomeTabProps {
  firstSectionTitleRef?: React.RefObject<any>;
  onFirstSectionTitleReady?: (nodeHandle: number | null) => void;
  onTabChange?: (tab: 'movie' | 'series' | 'anime' | 'variety') => void;
}

export default function HomeTab({ firstSectionTitleRef, onFirstSectionTitleReady, onTabChange }: HomeTabProps = {}) {
  const favoriteTitleRef = useRef<any>(null);
  const movieTitleRef = useRef<any>(null);
  const seriesTitleRef = useRef<any>(null);
  const animeTitleRef = useRef<any>(null);
  const varietyTitleRef = useRef<any>(null);

  const [playRecords, setPlayRecords] = useState<PlayRecord[]>([]);
  const [favorites, setFavorites] = useState<FavoriteItem[]>([]);
  const [hotMovies, setHotMovies] = useState<DoubanMovie[]>([]);
  const [hotTvShows, setHotTvShows] = useState<DoubanMovie[]>([]);
  const [hotShows, setHotShows] = useState<DoubanMovie[]>([]);
  const [todayBangumi, setTodayBangumi] = useState<BangumiItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showPlayRecords, setShowPlayRecords] = useState(false);
  const [showFavorites, setShowFavorites] = useState(false);

  useEffect(() => {
    loadAllData();
  }, []);

  // 当第一个 section 的 titleRef 准备好后，通知父组件
  useEffect(() => {
    if (firstSectionTitleRef?.current && onFirstSectionTitleReady) {
      const handle = findNodeHandle(firstSectionTitleRef.current);
      onFirstSectionTitleReady(handle);
    }
  }, [firstSectionTitleRef?.current, onFirstSectionTitleReady, showPlayRecords, showFavorites]);

  const loadAllData = async () => {
    setLoading(true);
    setError(null);

    try {
      // 并行加载所有数据
      const [playRecordsRes, favoritesRes, moviesRes, tvShowsRes, showsRes, bangumiRes] = await Promise.all([
        userDataCacheService.getPlayRecords(),
        userDataCacheService.getFavorites(),
        doubanService.getHotMovies({ pageLimit: 25, page: 0 }),
        doubanService.getHotTvShows({ pageLimit: 25, page: 0 }),
        doubanService.getHotShows({ pageLimit: 25, page: 0 }),
        bangumiService.getTodayCalendar(),
      ]);

      if (playRecordsRes.success && playRecordsRes.data && playRecordsRes.data.length > 0) {
        setPlayRecords(playRecordsRes.data);
        setShowPlayRecords(true);
      } else {
        setShowPlayRecords(false);
      }

      if (favoritesRes.success && favoritesRes.data && favoritesRes.data.length > 0) {
        setFavorites(favoritesRes.data);
        setShowFavorites(true);
      } else {
        setShowFavorites(false);
      }

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

  // 转换数据为 VideoInfo 格式
  const playRecordVideos: VideoInfo[] = playRecords.map(playRecordToVideoInfo);
  const favoriteVideos: VideoInfo[] = favorites.map(favoriteItemToVideoInfo);
  const hotMovieVideos: VideoInfo[] = hotMovies.map(doubanMovieToVideoInfo);
  const hotTvShowVideos: VideoInfo[] = hotTvShows.map(doubanMovieToVideoInfo);
  const hotShowVideos: VideoInfo[] = hotShows.map(doubanMovieToVideoInfo);
  const todayBangumiVideos: VideoInfo[] = todayBangumi.map(bangumiItemToVideoInfo);

  return (
    <View style={styles.container}>
      {error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>加载失败: {error}</Text>
        </View>
      )}
      {showPlayRecords && (
        <PlayRecordSection 
          title="继续观看" 
          videos={playRecordVideos}
          loading={loading}
          titleRef={firstSectionTitleRef}
          nextSectionTitleRef={showFavorites ? favoriteTitleRef : movieTitleRef}
        />
      )}
      {showFavorites && (
        <FavoriteSection 
          title="我的收藏" 
          videos={favoriteVideos}
          playRecords={playRecordVideos}
          loading={loading}
          titleRef={showPlayRecords ? favoriteTitleRef : firstSectionTitleRef}
          nextSectionTitleRef={movieTitleRef}
        />
      )}
      <RecommendSection 
        title="热门电影" 
        videos={hotMovieVideos}
        from="douban"
        loading={loading}
        titleRef={movieTitleRef}
        nextSectionTitleRef={seriesTitleRef}
        onTitlePress={() => onTabChange?.('movie')}
      />
      <RecommendSection 
        title="热门剧集" 
        videos={hotTvShowVideos}
        from="douban"
        loading={loading}
        titleRef={seriesTitleRef}
        nextSectionTitleRef={animeTitleRef}
        onTitlePress={() => onTabChange?.('series')}
      />
      <RecommendSection 
        title="新番放送" 
        videos={todayBangumiVideos}
        from="bangumi"
        loading={loading}
        titleRef={animeTitleRef}
        nextSectionTitleRef={varietyTitleRef}
      />
      <RecommendSection
        title="热门综艺" 
        videos={hotShowVideos}
        from="douban"
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
