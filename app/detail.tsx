import { StyleSheet, View, Text, ScrollView, BackHandler, Animated, Image, ActivityIndicator, useWindowDimensions, Pressable, TVFocusGuideView } from 'react-native';
import { useLocalSearchParams, router, useFocusEffect } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';
import { useEffect, useRef, useState, useCallback } from 'react';
import { doubanService } from '@/services/doubanService';
import { DoubanMovieDetails } from '@/types/douban';
import { Ionicons } from '@expo/vector-icons';
import PlaySourceSection from '@/components/PlaySourceSection';
import SearchService from '@/services/searchService';
import { VideoInfo, SearchResult, FavoriteItem } from '@/types/entities';
import { ScrollContext } from '@/contexts/ScrollContext';
import userDataCacheService from '@/services/userDataCacheService';

// 骨架屏组件
const Skeleton = ({ width, height, style }: { width?: number | string, height?: number | string, style?: any }) => {
  const opacity = useRef(new Animated.Value(0.3)).current;

  useEffect(() => {
    Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, {
          toValue: 0.7,
          duration: 800,
          useNativeDriver: true,
        }),
        Animated.timing(opacity, {
          toValue: 0.3,
          duration: 800,
          useNativeDriver: true,
        }),
      ])
    ).start();
  }, []);

  return (
    <Animated.View
      style={[
        {
          width,
          height,
          backgroundColor: 'rgba(255, 255, 255, 0.1)',
          borderRadius: 4,
          opacity,
        },
        style,
      ]}
    />
  );
};

export default function DetailScreen() {
  const params = useLocalSearchParams();
  const { title, doubanId, from, year } = params;
  const { height: screenHeight } = useWindowDimensions();

  const [details, setDetails] = useState<DoubanMovieDetails | null>(null);
  const [posterUrl, setPosterUrl] = useState<string | null>(null);
  const [highResPosterUrl, setHighResPosterUrl] = useState<string | null>(null);

  // 播放源相关状态
  const [sources, setSources] = useState<VideoInfo[]>([]);
  const [searching, setSearching] = useState(false);
  const [playRecords, setPlayRecords] = useState<VideoInfo[]>([]);
  const [playRecordsLoaded, setPlayRecordsLoaded] = useState(false);
  const [selectedSourceKey, setSelectedSourceKey] = useState<string | null>(null);
  const currentDoubanIdRef = useRef<string | undefined>(doubanId as string | undefined);
  const [favorites, setFavorites] = useState<FavoriteItem[]>([]);
  
  // 按钮动画
  const buttonAnimations = useRef({
    continuePlay: new Animated.Value(1),
    play: new Animated.Value(1),
    delete: new Animated.Value(1),
    favorite: new Animated.Value(1),
  }).current;
  const [focusedButton, setFocusedButton] = useState<string | null>(null);
  const [buttonGroupKey, setButtonGroupKey] = useState(0);
  
  // 按钮 ref
  const continuePlayButtonRef = useRef<any>(null);
  const playButtonRef = useRef<any>(null);

  // ScrollContext 相关
  const scrollViewRef = useRef<ScrollView>(null);
  const measureContainerRef = useRef<View>(null);
  const isPageFocusedRef = useRef(true);
  const [measureElement, setMeasureElement] = useState<React.ReactNode>(null);

  const renderMeasureElement = useCallback((element: React.ReactNode) => {
    setMeasureElement(element);
  }, []);

  const clearMeasureElement = useCallback(() => {
    setMeasureElement(null);
  }, []);

  // 监听页面焦点状态
  useFocusEffect(
    useCallback(() => {
      isPageFocusedRef.current = true;
      return () => {
        isPageFocusedRef.current = false;
      };
    }, [])
  );

  const REQUEST_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
    'Referer': 'https://movie.douban.com/',
  };

  // 动画值
  const translateX = useRef(new Animated.Value(50)).current;
  const opacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    console.log('详情页参数:', { title, doubanId, from, year });

    if (from === 'douban' && doubanId) {
      fetchDoubanDetails(doubanId as string);
      currentDoubanIdRef.current = doubanId as string;
    }

    // 加载播放记录和收藏
    loadPlayRecords();
    loadFavorites();
  }, [title, doubanId, from]);

  const loadPlayRecords = async () => {
    try {
      const result = await userDataCacheService.getPlayRecords();
      if (result.success && result.data) {
        setPlayRecords(result.data);
      }
    } catch (error) {
      console.error('加载播放记录失败:', error);
    } finally {
      setPlayRecordsLoaded(true);
    }
  };

  const loadFavorites = async () => {
    try {
      const result = await userDataCacheService.getFavorites();
      if (result.success && result.data) {
        setFavorites(result.data);
      }
    } catch (error) {
      console.error('加载收藏失败:', error);
    }
  };

  // 处理选中源的变化
  const handleSourceSelect = (sourceKey: string) => {
    setSelectedSourceKey(sourceKey);

    // 查找对应的视频信息
    const selectedVideo = sources.find(v => `${v.source}_${v.id}` === sourceKey);
    if (!selectedVideo) return;

    const selectedDoubanId = selectedVideo.doubanId;

    // 先滚动到顶部（带动画）
    scrollViewRef.current?.scrollTo({ y: 0, animated: true });
    
    // 延迟改变 key 来触发聚焦，让滚动动画先执行
    setTimeout(() => {
      setButtonGroupKey(prev => prev + 1);
    }, 200);

    // 如果 selectedDoubanId 不存在（为空或为 0），且入参中包含 doubanId，则切换到入参的 doubanId
    if ((!selectedDoubanId || selectedDoubanId === '0') && doubanId) {
      const paramDoubanId = doubanId as string;
      // 如果和当前页面展示的 douban 一致，无动作
      if (paramDoubanId === currentDoubanIdRef.current) {
        return;
      }
      // 切换到入参的 doubanId
      currentDoubanIdRef.current = paramDoubanId;
      fetchDoubanDetails(paramDoubanId);
      return;
    }

    // 如果 doubanId 不存在（为空或为 0），无动作
    if (!selectedDoubanId || selectedDoubanId === '0') {
      return;
    }

    // 如果和当前页面展示的 douban 一致，无动作
    if (selectedDoubanId === currentDoubanIdRef.current) {
      return;
    }

    // doubanId 不一致，重新获取详情
    currentDoubanIdRef.current = selectedDoubanId;
    fetchDoubanDetails(selectedDoubanId);
  };

  // 获取当前选中源的信息
  const getSelectedSourceInfo = () => {
    if (!selectedSourceKey) return null;
    return sources.find(v => `${v.source}_${v.id}` === selectedSourceKey);
  };

  // 检查当前源是否有播放记录
  const hasPlayRecord = () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return false;
    return playRecords.some(r => r.source === selectedSource.source && r.id === selectedSource.id);
  };

  // 检查当前源是否已收藏
  const isFavorited = () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return false;
    return favorites.some(f => f.source === selectedSource.source && f.id === selectedSource.id);
  };

  // 处理播放
  const handlePlay = () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return;
    console.log('播放:', selectedSource);
    // TODO: 跳转到播放页
  };

  // 处理继续播放
  const handleContinuePlay = () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return;
    console.log('继续播放:', selectedSource);
    // TODO: 跳转到播放页，从上次位置继续
  };

  // 处理收藏切换
  const handleToggleFavorite = async () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return;

    if (isFavorited()) {
      // 取消收藏
      const result = await userDataCacheService.removeFavorite(selectedSource.source, selectedSource.id);
      if (result.success) {
        loadFavorites();
      }
    } else {
      // 添加收藏
      const favoriteData = {
        title: selectedSource.title,
        sourceName: selectedSource.sourceName,
        year: selectedSource.year,
        cover: selectedSource.cover,
        totalEpisodes: selectedSource.totalEpisodes,
        origin: 'detail',
      };
      const result = await userDataCacheService.addFavorite(selectedSource.source, selectedSource.id, favoriteData);
      if (result.success) {
        loadFavorites();
      }
    }
  };

  // 处理删除播放记录
  const handleDeletePlayRecord = async () => {
    const selectedSource = getSelectedSourceInfo();
    if (!selectedSource) return;

    const result = await userDataCacheService.deletePlayRecord(selectedSource.source, selectedSource.id);
    if (result.success) {
      loadPlayRecords();
    }
  };

  // 搜索相关资源
  useEffect(() => {
    // 只有在播放记录加载完成后才开始搜索
    if (from === 'douban' && title && playRecordsLoaded) {
      const startSearch = async () => {
        setSearching(true);
        setSources([]);

        try {
          await SearchService.startSearch(title as string);
        } catch (error) {
          console.error('启动搜索失败:', error);
          setSearching(false);
        }
      };

      startSearch();

      // 监听搜索结果
      const removeResultsListener = SearchService.addEventListener('results', (results: SearchResult[]) => {
        // 创建播放记录的 Map，key 为 source+id
        const playRecordMap = new Map<string, VideoInfo>();
        playRecords.forEach(record => {
          const key = `${record.source}_${record.id}`;
          playRecordMap.set(key, record);
        });
        // 过滤和转换结果
        const newVideos = results
          .filter(result => {
            // 必须标题完全一致
            if (result.title !== title) {
              return false;
            }
            // 如果有年份参数，进行过滤
            if (year) {
              return result.year === year || result.year === String(year);
            }
            return true;
          })
          .map((result, index) => {
            const key = `${result.source}_${result.id}`;
            const playRecord = playRecordMap.get(key);

            // 如果有播放记录，使用播放记录的 index、playTime 和 totalTime
            return {
              id: result.id,
              source: result.source,
              title: result.title,
              sourceName: result.sourceName,
              year: result.year,
              cover: result.poster,
              index: playRecord?.index ?? 0,
              totalEpisodes: result.episodes?.length || 0,
              playTime: playRecord?.playTime ?? 0,
              totalTime: playRecord?.totalTime ?? 0,
              saveTime: Date.now(),
              searchTitle: title as string,
              doubanId: result.doubanId ? String(result.doubanId) : undefined,
            };
          });

        setSources(prev => {
          // 去重合并
          const existingIds = new Set(prev.map(v => `${v.source}_${v.id}`));
          const uniqueNewVideos = newVideos.filter(v => !existingIds.has(`${v.source}_${v.id}`));
          const updatedSources = [...prev, ...uniqueNewVideos];
          
          // 如果还没有选中源且有结果，自动选中第一个
          if (!selectedSourceKey && updatedSources.length > 0) {
            setSelectedSourceKey(`${updatedSources[0].source}_${updatedSources[0].id}`);
          }
          
          return updatedSources;
        });
      });

      const removeCompleteListener = SearchService.addEventListener('complete', () => {
        setSearching(false);
      });

      const removeErrorListener = SearchService.addEventListener('error', (error) => {
        console.error('搜索出错:', error);
        // 出错也不要一直 loading
        setSearching(false);
      });

      return () => {
        removeResultsListener();
        removeCompleteListener();
        removeErrorListener();
        SearchService.stopSearch();
      };
    }
  }, [from, title, year, playRecordsLoaded]);

  useEffect(() => {
    if (details?.poster) {
      // 先显示默认图片
      setPosterUrl(details.poster);

      // 尝试加载大图
      if (details.poster.includes('s_ratio_poster')) {
        setHighResPosterUrl(details.poster.replace('s_ratio_poster', 'l_ratio_poster'));
      }
    }
  }, [details]);

  const fetchDoubanDetails = async (id: string) => {
    try {
      const response = await doubanService.getDoubanDetails(id);
      if (response.success && response.data) {
        setDetails(response.data);
      }
    } catch (error) {
      console.error('获取详情失败:', error);
    }
  };

  // 进入动画
  useEffect(() => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 200,
        useNativeDriver: true,
      }),
      Animated.timing(translateX, {
        toValue: 0,
        duration: 200,
        useNativeDriver: true,
      }),
    ]).start();
  }, []);

  // 监听返回键
  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      // 退出动画
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 0,
          duration: 100,
          useNativeDriver: true,
        }),
        Animated.timing(translateX, {
          toValue: 50,
          duration: 100,
          useNativeDriver: true,
        }),
      ]).start(() => {
        router.back();
      });
      return true;
    });

    return () => backHandler.remove();
  }, []);

  const handleVideoPress = useCallback((video: VideoInfo) => {
    // TODO: 处理点击事件，跳转到播放页或详情页
    console.log('点击推荐视频:', video);
    // 这里暂时只打印，后续根据需求实现跳转逻辑
    // 通常是跳转到播放页
    // router.push({ pathname: '/player', params: { ... } });
  }, []);

  // 按钮聚焦时滚动到顶部
  const handleButtonFocus = useCallback((buttonKey: string) => {
    scrollViewRef.current?.scrollTo({ y: 0, animated: true });
    setFocusedButton(buttonKey);
  }, []);

  // 按钮失焦
  const handleButtonBlur = useCallback(() => {
    setFocusedButton(null);
  }, []);

  // 处理按钮动画
  useEffect(() => {
    Object.keys(buttonAnimations).forEach((key) => {
      const isFocused = focusedButton === key;
      Animated.spring(buttonAnimations[key as keyof typeof buttonAnimations], {
        toValue: isFocused ? 1.05 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedButton]);

  return (
    <LinearGradient
      colors={['#0a0a0a', '#1a1a1a', '#0f0f0f', '#050505']}
      locations={[0, 0.4, 0.7, 1]}
      style={styles.container}
    >
      <ScrollContext.Provider value={{ scrollViewRef, measureContainerRef, renderMeasureElement, clearMeasureElement, isPageFocusedRef }}>
        <Animated.View
          style={[
            styles.animatedContainer,
            {
              opacity,
              transform: [{ translateX }],
            },
          ]}
        >
          <ScrollView
            ref={scrollViewRef}
            style={styles.scrollView}
            showsVerticalScrollIndicator={false}
            scrollEventThrottle={16}
          >
            <View style={styles.content}>
              <View style={styles.detailContainer}>
                {/* 顶部详情区域 */}
                <View style={[styles.topSection, { height: screenHeight * 0.6 }]}>
                  {/* 左侧区域 */}
                  <View style={styles.leftArea}>
                    {/* 左侧内容区 */}
                    <View style={styles.leftContent}>
                    <Text style={styles.mainTitle} numberOfLines={2}>{title}</Text>

                    {details ? (
                      <>
                        <View style={styles.metaInfo}>
                          <View style={styles.tagsRow}>
                            {details.genres.map((genre, index) => (
                              <View key={index} style={styles.tagBadge}>
                                <Text style={styles.tagText}>{genre}</Text>
                              </View>
                            ))}
                            {details.rate && (
                              <View style={styles.ratingContainer}>
                                <Ionicons name="star" size={14} color="#E91E63" style={styles.starIcon} />
                                <Text style={styles.ratingText}>{details.rate}</Text>
                              </View>
                            )}
                          </View>

                          <Text style={styles.releaseDate}>
                            {details.releaseDate || details.year}
                            {details.countries.length > 0 && ` · ${details.countries[0]}`}
                            {details.duration && ` · ${details.duration}`}
                          </Text>
                        </View>

                        {details.summary && (
                          <Text style={styles.summary} numberOfLines={8} ellipsizeMode="tail">
                            {details.summary}
                          </Text>
                        )}
                      </>
                    ) : (
                      // 骨架屏加载状态
                      <>
                        <View style={styles.metaInfo}>
                          <View style={{ flexDirection: 'row', gap: 8, marginBottom: 8 }}>
                            <Skeleton width={60} height={20} />
                            <Skeleton width={60} height={20} />
                            <Skeleton width={40} height={20} />
                          </View>
                          <Skeleton width={200} height={16} />
                        </View>
                        <View style={{ gap: 8 }}>
                          <Skeleton width="100%" height={16} />
                          <Skeleton width="100%" height={16} />
                          <Skeleton width="80%" height={16} />
                        </View>
                      </>
                    )}
                    </View>

                    {/* 操作按钮组 */}
                    <View style={styles.actionButtonsWrapper}>
                      <TVFocusGuideView
                        key={buttonGroupKey}
                        style={styles.actionButtons}
                        trapFocusLeft
                        trapFocusRight
                        autoFocus={false}
                      >
                        {hasPlayRecord() ? (
                          <>
                            {/* 有播放记录：继续播放 + 播放 + 删除 + 收藏 */}
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.continuePlay }] }}>
                              <Pressable
                                ref={continuePlayButtonRef}
                                style={({ focused }) => [
                                  styles.primaryButton,
                                  focused && styles.buttonFocused
                                ]}
                                onPress={handleContinuePlay}
                                onFocus={() => handleButtonFocus('continuePlay')}
                                onBlur={handleButtonBlur}
                                hasTVPreferredFocus={true}
                              >
                                {({ focused }) => (
                                  <>
                                    <Ionicons name="play-circle" size={20} color={focused ? '#0a0a0a' : '#ffffff'} />
                                    <Text style={[styles.primaryButtonText, focused && styles.buttonTextFocused]}>继续播放</Text>
                                  </>
                                )}
                              </Pressable>
                            </Animated.View>
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.play }] }}>
                              <Pressable
                                style={({ focused }) => [
                                  styles.secondaryButton,
                                  focused && styles.buttonFocused
                                ]}
                                onPress={handlePlay}
                                onFocus={() => handleButtonFocus('play')}
                                onBlur={handleButtonBlur}
                              >
                                {({ focused }) => (
                                  <>
                                    <Ionicons name="play" size={18} color={focused ? '#0a0a0a' : '#ffffff'} />
                                    <Text style={[styles.secondaryButtonText, focused && styles.buttonTextFocused]}>播放</Text>
                                  </>
                                )}
                              </Pressable>
                            </Animated.View>
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.delete }] }}>
                              <Pressable
                                style={({ focused }) => [
                                  styles.iconButton,
                                  focused && styles.iconButtonFocused
                                ]}
                                onPress={handleDeletePlayRecord}
                                onFocus={() => handleButtonFocus('delete')}
                                onBlur={handleButtonBlur}
                              >
                                {({ focused }) => (
                                  <Ionicons name="trash-outline" size={20} color={focused ? '#0a0a0a' : '#ffffff'} />
                                )}
                              </Pressable>
                            </Animated.View>
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.favorite }] }}>
                              <Pressable
                                style={({ focused }) => [
                                  styles.iconButton,
                                  focused && styles.iconButtonFocused
                                ]}
                                onPress={handleToggleFavorite}
                                onFocus={() => handleButtonFocus('favorite')}
                                onBlur={handleButtonBlur}
                              >
                                {({ focused }) => (
                                  <Ionicons
                                    name={isFavorited() ? "heart" : "heart-outline"}
                                    size={20}
                                    color={focused ? '#0a0a0a' : (isFavorited() ? "#E91E63" : "#ffffff")}
                                  />
                                )}
                              </Pressable>
                            </Animated.View>
                          </>
                        ) : (
                          <>
                            {/* 无播放记录：播放 + 收藏 */}
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.play }] }}>
                              <Pressable
                                ref={playButtonRef}
                                style={({ focused }) => [
                                  styles.primaryButton,
                                  !selectedSourceKey && styles.buttonDisabled,
                                  focused && selectedSourceKey && styles.buttonFocused
                                ]}
                                onPress={handlePlay}
                                onFocus={() => handleButtonFocus('play')}
                                onBlur={handleButtonBlur}
                                disabled={!selectedSourceKey}
                                hasTVPreferredFocus={true}
                              >
                                {({ focused }) => (
                                  <>
                                    <Ionicons name="play" size={20} color={focused && selectedSourceKey ? '#0a0a0a' : '#ffffff'} />
                                    <Text style={[styles.primaryButtonText, focused && selectedSourceKey && styles.buttonTextFocused]}>播放</Text>
                                  </>
                                )}
                              </Pressable>
                            </Animated.View>
                            <Animated.View style={{ transform: [{ scale: buttonAnimations.favorite }] }}>
                              <Pressable
                                style={({ focused }) => [
                                  styles.iconButton,
                                  !selectedSourceKey && styles.buttonDisabled,
                                  focused && selectedSourceKey && styles.iconButtonFocused
                                ]}
                                onPress={handleToggleFavorite}
                                onFocus={() => handleButtonFocus('favorite')}
                                onBlur={handleButtonBlur}
                                disabled={!selectedSourceKey}
                              >
                                {({ focused }) => (
                                  <Ionicons
                                    name={isFavorited() ? "heart" : "heart-outline"}
                                    size={20}
                                    color={focused && selectedSourceKey ? '#0a0a0a' : (isFavorited() ? "#E91E63" : "#ffffff")}
                                  />
                                )}
                              </Pressable>
                            </Animated.View>
                          </>
                        )}
                        {/* 当前源名称 */}
                        {selectedSourceKey && (
                          <View style={styles.sourceNameBadge}>
                            <Text style={styles.sourceNameText}>
                              {getSelectedSourceInfo()?.sourceName || '未知源'}
                            </Text>
                          </View>
                        )}
                      </TVFocusGuideView>
                    </View>
                  </View>

                  {/* 右侧封面 */}
                  <View style={styles.rightCover}>
                    {details ? (
                      <>
                        <Image
                          source={{
                            uri: posterUrl || details.poster,
                            headers: REQUEST_HEADERS
                          }}
                          style={styles.posterImage}
                          resizeMode="cover"
                        />
                        {highResPosterUrl && (
                          <Image
                            source={{
                              uri: highResPosterUrl,
                              headers: REQUEST_HEADERS
                            }}
                            style={{ width: 0, height: 0, opacity: 0 }}
                            onLoad={() => {
                              setPosterUrl(highResPosterUrl);
                              setHighResPosterUrl(null);
                            }}
                          />
                        )}
                      </>
                    ) : (
                      <Skeleton width="100%" height="100%" />
                    )}
                  </View>
                </View>

                {/* 播放源 */}
                <View style={styles.sourceContainer}>
                  {sources.length > 0 || searching ? (
                    <PlaySourceSection
                      title="播放源"
                      videos={sources}
                      loading={searching}
                      onTitlePress={() => { }}
                      selectedSourceKey={selectedSourceKey}
                      onSourceSelect={handleSourceSelect}
                    />
                  ) : (
                    <View style={styles.noSourceContainer}>
                      <Ionicons name="film-outline" size={48} color="rgba(255, 255, 255, 0.3)" />
                      <Text style={styles.noSourceText}>暂无可用播放源</Text>
                    </View>
                  )}
                </View>


              </View>
            </View>
          </ScrollView>
        </Animated.View>

        {/* 全局测量容器 - 不受任何父容器宽度限制 */}
        <View
          ref={measureContainerRef}
          style={styles.globalMeasureContainer}
        >
          {measureElement}
        </View>
      </ScrollContext.Provider>
    </LinearGradient>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  animatedContainer: {
    flex: 1,
  },
  scrollView: {
    flex: 1,
  },
  content: {
    paddingHorizontal: 0,
    paddingTop: 50,
    paddingBottom: 10,
  },
  loadingContainer: {
    height: 300,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    color: 'rgba(255, 255, 255, 0.6)',
    marginTop: 12,
    fontSize: 14,
  },
  detailContainer: {
    flex: 1,
  },
  topSection: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 10,
    paddingHorizontal: 60,
  },
  leftArea: {
    flex: 1,
    marginRight: 40,
    height: '100%',
  },
  leftContent: {
    height: '70%',
  },
  rightCover: {
    height: '100%',
    aspectRatio: 2 / 3,
    borderRadius: 8,
    overflow: 'hidden',
    elevation: 5,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    backgroundColor: '#2a2a2a',
  },
  posterImage: {
    width: '100%',
    height: '100%',
  },
  mainTitle: {
    fontSize: 24,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 12,
    lineHeight: 32,
  },
  metaInfo: {
    marginBottom: 8,
  },
  tagsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
    alignItems: 'center',
  },
  tagBadge: {
    marginRight: 0,
  },
  tagText: {
    color: 'rgba(255, 255, 255, 0.6)',
    fontSize: 12,
  },
  ratingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 4,
  },
  starIcon: {
    marginRight: 4,
  },
  ratingText: {
    color: 'rgba(255, 255, 255, 0.8)',
    fontSize: 12,
    fontWeight: '700',
  },
  releaseDate: {
    fontSize: 12,
    color: 'rgba(255, 255, 255, 0.5)',
  },
  summary: {
    fontSize: 13,
    color: 'rgba(255, 255, 255, 0.7)',
    lineHeight: 20,
  },
  sourceContainer: {
    marginTop: 20,
    marginBottom: 0,
  },
  noSourceContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 60,
    paddingHorizontal: 40,
  },
  noSourceText: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.5)',
    marginTop: 16,
    textAlign: 'center',
  },
  globalMeasureContainer: {
    position: 'absolute',
    opacity: 0,
    left: 0,
    top: 0,
    zIndex: -999,
    pointerEvents: 'none',
    flexDirection: 'row',
    maxWidth: 5000,
  },
  actionButtonsWrapper: {
    height: '30%',
    justifyContent: 'flex-end',
  },
  actionButtons: {
    flexDirection: 'row',
    gap: 12,
    alignItems: 'center',
  },
  sourceNameBadge: {
    marginLeft: 'auto',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    borderWidth: 0.5,
    borderColor: 'rgba(255, 255, 255, 0.3)',
  },
  sourceNameText: {
    fontSize: 12,
    color: 'rgba(255, 255, 255, 0.5)',
    fontWeight: '600',
  },
  primaryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 6,
    gap: 8,
  },
  primaryButtonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '600',
  },
  buttonTextFocused: {
    color: '#0a0a0a',
  },
  secondaryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 6,
    gap: 6,
  },
  secondaryButtonText: {
    color: '#ffffff',
    fontSize: 13,
    fontWeight: '600',
  },
  iconButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  buttonDisabled: {
    opacity: 0.4,
  },
  buttonFocused: {
    backgroundColor: '#ffffff',
  },
  iconButtonFocused: {
    backgroundColor: '#ffffff',
  },
});
