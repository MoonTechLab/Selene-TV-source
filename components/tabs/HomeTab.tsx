import { StyleSheet, View, Text, findNodeHandle } from 'react-native';
import { useRef, useEffect, useState, forwardRef, useImperativeHandle } from 'react';
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

export interface HomeTabRef {
  refresh: (forceSource?: boolean) => void;
}

const HomeTab = forwardRef<HomeTabRef, HomeTabProps>(({ firstSectionTitleRef, onFirstSectionTitleReady, onTabChange }, ref) => {
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
  const [error, setError] = useState<string | null>(null);
  const [showPlayRecords, setShowPlayRecords] = useState(false);
  const [showFavorites, setShowFavorites] = useState(false);
  
  // 独立的 loading 状态
  const [loadingUserData, setLoadingUserData] = useState(true);
  const [loadingMovies, setLoadingMovies] = useState(true);
  const [loadingTvShows, setLoadingTvShows] = useState(true);
  const [loadingShows, setLoadingShows] = useState(true);
  const [loadingBangumi, setLoadingBangumi] = useState(true);

  useEffect(() => {
    loadAllData();
  }, []);

  // 暴露刷新方法
  useImperativeHandle(ref, () => ({
    refresh: () => {
      loadAllData(true);
    },
  }));

  // 当第一个 section 的 titleRef 准备好后，通知父组件
  useEffect(() => {
    if (firstSectionTitleRef?.current && onFirstSectionTitleReady) {
      const handle = findNodeHandle(firstSectionTitleRef.current);
      onFirstSectionTitleReady(handle);
    }
  }, [firstSectionTitleRef?.current, onFirstSectionTitleReady, showPlayRecords, showFavorites]);

  const loadAllData = async (forceSource: boolean = false) => {
    setError(null);
    
    // 设置所有 loading 状态为 true
    setLoadingUserData(true);
    setLoadingMovies(true);
    setLoadingTvShows(true);
    setLoadingShows(true);
    setLoadingBangumi(true);

    // 加载播放记录和收藏（一起加载）
    Promise.all([
      userDataCacheService.getPlayRecords(forceSource),
      userDataCacheService.getFavorites(forceSource),
    ]).then(([playRecordsRes, favoritesRes]) => {
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
    }).catch(() => {
      setShowPlayRecords(false);
      setShowFavorites(false);
    }).finally(() => {
      setLoadingUserData(false);
    });

    // 加载热门电影
    doubanService.getHotMovies({ pageLimit: 25, page: 0, forceSource }).then((moviesRes) => {
      if (moviesRes.success && moviesRes.data) {
        setHotMovies(moviesRes.data);
      }
    }).catch(() => {
      // 忽略错误
    }).finally(() => {
      setLoadingMovies(false);
    });

    // 加载热门剧集
    doubanService.getHotTvShows({ pageLimit: 25, page: 0, forceSource }).then((tvShowsRes) => {
      if (tvShowsRes.success && tvShowsRes.data) {
        setHotTvShows(tvShowsRes.data);
      }
    }).catch(() => {
      // 忽略错误
    }).finally(() => {
      setLoadingTvShows(false);
    });

    // 加载热门综艺
    doubanService.getHotShows({ pageLimit: 25, page: 0, forceSource }).then((showsRes) => {
      if (showsRes.success && showsRes.data) {
        setHotShows(showsRes.data);
      }
    }).catch(() => {
      // 忽略错误
    }).finally(() => {
      setLoadingShows(false);
    });

    // 加载新番放送
    bangumiService.getTodayCalendar(forceSource).then((bangumiRes) => {
      if (bangumiRes.success && bangumiRes.data) {
        setTodayBangumi(bangumiRes.data);
      }
    }).catch(() => {
      // 忽略错误
    }).finally(() => {
      setLoadingBangumi(false);
    });
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
          loading={loadingUserData}
          titleRef={firstSectionTitleRef}
          nextSectionTitleRef={showFavorites ? favoriteTitleRef : movieTitleRef}
        />
      )}
      {showFavorites && (
        <FavoriteSection 
          title="我的收藏" 
          videos={favoriteVideos}
          playRecords={playRecordVideos}
          loading={loadingUserData}
          titleRef={showPlayRecords ? favoriteTitleRef : firstSectionTitleRef}
          nextSectionTitleRef={movieTitleRef}
        />
      )}
      <RecommendSection 
        title="热门电影" 
        videos={hotMovieVideos}
        from="douban"
        loading={loadingMovies}
        titleRef={movieTitleRef}
        nextSectionTitleRef={seriesTitleRef}
        onTitlePress={() => onTabChange?.('movie')}
      />
      <RecommendSection 
        title="热门剧集" 
        videos={hotTvShowVideos}
        from="douban"
        loading={loadingTvShows}
        titleRef={seriesTitleRef}
        nextSectionTitleRef={animeTitleRef}
        onTitlePress={() => onTabChange?.('series')}
      />
      <RecommendSection 
        title="新番放送" 
        videos={todayBangumiVideos}
        from="bangumi"
        loading={loadingBangumi}
        titleRef={animeTitleRef}
        nextSectionTitleRef={varietyTitleRef}
      />
      <RecommendSection
        title="热门综艺" 
        videos={hotShowVideos}
        from="douban"
        loading={loadingShows}
        titleRef={varietyTitleRef}
        isLast={true}
      />
    </View>
  );
});

HomeTab.displayName = 'HomeTab';

export default HomeTab;

const styles = StyleSheet.create({
  container: {
    paddingTop: 10,
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
