import { StyleSheet, Text, ScrollView, Pressable, TVFocusGuideView, useWindowDimensions, findNodeHandle, BackHandler, Animated } from 'react-native';
import { useRef, useState, useEffect, useMemo } from 'react';
import VideoCard from './VideoCard';
import VideoCardSkeleton from './VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import { VideoInfo } from '@/types/entities';

interface FavoriteSectionProps {
  title: string;
  videos: VideoInfo[];
  playRecords?: VideoInfo[];
  onTitlePress?: () => void;
  titleRef?: React.RefObject<any>;
  nextSectionTitleRef?: React.RefObject<any>;
  isLast?: boolean;
  loading?: boolean;
}

export default function FavoriteSection({ title, videos, playRecords = [], onTitlePress, titleRef, nextSectionTitleRef, isLast = false, loading = false }: FavoriteSectionProps) {
  const scrollViewRef = useRef<ScrollView>(null);
  const sectionRef = useRef<any>(null);
  const titlePressableRef = useRef<any>(null);
  const cardsWrapperRef = useRef<any>(null);
  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const currentScrollX = useRef(0);
  const hasScrolledVertically = useRef(false);
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
  const [titleNodeHandle, setTitleNodeHandle] = useState<number | null>(null);
  const [nextSectionTitleNodeHandle, setNextSectionTitleNodeHandle] = useState<number | null>(null);
  const titleFocusAnim = useRef(new Animated.Value(0)).current;

  // 合并播放记录的 index 到收藏项
  const mergedVideos = useMemo(() => {
    if (!playRecords || playRecords.length === 0) {
      return videos;
    }

    // 创建播放记录的 Map，key 为 source+id
    const playRecordMap = new Map<string, number>();
    playRecords.forEach(record => {
      const key = `${record.source}_${record.id}`;
      playRecordMap.set(key, record.index);
    });

    // 合并 index
    return videos.map(video => {
      const key = `${video.source}_${video.id}`;
      const recordIndex = playRecordMap.get(key);
      
      if (recordIndex !== undefined) {
        return { ...video, index: recordIndex };
      }
      return video;
    });
  }, [videos, playRecords]);

  const resetScroll = () => {
    if (scrollViewRef.current) {
      scrollViewRef.current.scrollTo({ x: 0, animated: true });
      currentScrollX.current = 0;
      hasScrolledVertically.current = false;
    }
  };

  const handleTitleFocus = () => {
    Animated.spring(titleFocusAnim, {
      toValue: 1,
      useNativeDriver: true,
      friction: 8,
      tension: 100,
    }).start();

    if (!mainScrollViewRef?.current || !titlePressableRef.current) return;

    // 测量标题在主滚动视图中的位置
    titlePressableRef.current.measureLayout(
      mainScrollViewRef.current as any,
      (_x: number, y: number, _width: number, height: number) => {
        // 计算垂直居中位置
        const scrollToY = Math.max(0, y - (screenHeight / 2) + (height / 2));
        mainScrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
      }
    );
  };

  const handleTitleBlur = () => {
    Animated.timing(titleFocusAnim, {
      toValue: 0,
      duration: 150,
      useNativeDriver: true,
    }).start();
  };

  const handleScroll = (event: any) => {
    currentScrollX.current = event.nativeEvent.contentOffset.x;
  };

  const handleCardFocus = (index: number, cardRef: any) => {
    if (!scrollViewRef.current) return;

    // 卡片实际宽度 90 + 右边距 20 = 110
    const cardTotalWidth = 110;
    const cardActualWidth = 90;
    const contentPadding = 16; // ScrollView 的 paddingHorizontal
    const cardPosition = index * cardTotalWidth; // 卡片左边缘相对于内容区的位置
    const viewportStart = currentScrollX.current; // 视口左边缘
    const viewportEnd = currentScrollX.current + screenWidth; // 视口右边缘

    // 当前卡片在内容区中的绝对位置（包含 padding）
    const cardAbsoluteLeft = contentPadding + cardPosition;
    const cardAbsoluteRight = cardAbsoluteLeft + cardActualWidth;

    // 下一个卡片（右侧）的位置
    const nextCardPosition = (index + 1) * cardTotalWidth;
    const nextCardAbsoluteLeft = contentPadding + nextCardPosition;
    const nextCardAbsoluteRight = nextCardAbsoluteLeft + cardActualWidth;
    // 上一个卡片（左侧）的位置
    const prevCardPosition = (index - 1) * cardTotalWidth;
    const prevCardAbsoluteLeft = contentPadding + prevCardPosition;
    const prevCardAbsoluteRight = prevCardAbsoluteLeft + cardActualWidth;

    // 向右滚动：如果下一个卡片右边缘超出视口，滚动刚好使下一个卡片完整可见
    // 对称于左滚动，右边也留出 contentPadding 的空间
    if (nextCardAbsoluteRight > viewportEnd - contentPadding) {
      // 计算需要滚动的距离
      const scrollDistance = nextCardAbsoluteRight - (viewportEnd - contentPadding);
      const newScrollX = currentScrollX.current + scrollDistance;
      currentScrollX.current = newScrollX;
      scrollViewRef.current.scrollTo({ x: newScrollX, animated: true });
    }
    // 向左滚动：如果上一个卡片左边缘超出视口，滚动刚好使上一个卡片完整可见
    else if (prevCardAbsoluteLeft < viewportStart + contentPadding) {
      // 计算需要滚动的距离：视口左边缘 - 上一个卡片左边缘 + 左边距
      const scrollDistance = (viewportStart + contentPadding) - prevCardAbsoluteLeft;
      const newScrollX = Math.max(0, currentScrollX.current - scrollDistance);
      currentScrollX.current = newScrollX;
      scrollViewRef.current.scrollTo({ x: newScrollX, animated: true });
    }

    // 垂直滚动到居中位置（只在第一次进入这一行时滚动）
    if (!hasScrolledVertically.current && mainScrollViewRef?.current && cardRef) {
      cardRef.measureLayout(
        mainScrollViewRef.current as any,
        (_x: number, y: number, _width: number, height: number) => {
          const scrollToY = Math.max(0, y - (screenHeight / 2) + (height / 2));
          mainScrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
          hasScrolledVertically.current = true;
        }
      );
    }
  };

  useEffect(() => {
    if (titlePressableRef.current) {
      const handle = findNodeHandle(titlePressableRef.current);
      setTitleNodeHandle(handle);
    }
  }, [title]);

  // 使用 videos 作为依赖，当列表渲染完成后再检查 nextSectionTitleRef
  useEffect(() => {
      if (nextSectionTitleRef?.current) {
        const handle = findNodeHandle(nextSectionTitleRef.current);
        setNextSectionTitleNodeHandle(handle);
      }
  }, [nextSectionTitleRef]);

  // 监听返回键，重置水平滚动
  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      resetScroll();
      return false; // 不阻止默认行为，让事件继续传播
    });

    return () => backHandler.remove();
  }, []);

  return (
    <TVFocusGuideView
      ref={sectionRef}
      style={styles.container}
      trapFocusUp
      trapFocusDown
      autoFocus={false}
    >
      <TVFocusGuideView
        style={styles.titleWrapper}
        trapFocusLeft
        trapFocusRight
        autoFocus={false}
      >
        <Pressable
          ref={(ref) => {
            titlePressableRef.current = ref;
            if (titleRef) {
              titleRef.current = ref;
            }
          }}
          onPress={onTitlePress}
          onFocus={handleTitleFocus}
          onBlur={handleTitleBlur}
          android_disableSound={true}
          style={{ backgroundColor: 'transparent' }}
        >
          <Animated.View
            style={[
              styles.titleContainer,
              {
                backgroundColor: titleFocusAnim.interpolate({
                  inputRange: [0, 1],
                  outputRange: ['rgba(255, 255, 255, 0)', 'rgba(255, 255, 255, 0.2)'],
                }),
                transform: [
                  {
                    scale: titleFocusAnim.interpolate({
                      inputRange: [0, 1],
                      outputRange: [1, 1.1],
                    }),
                  },
                ],
              },
            ]}
          >
            <Text style={styles.title}>{title}</Text>
          </Animated.View>
        </Pressable>
      </TVFocusGuideView>

      <TVFocusGuideView
        ref={cardsWrapperRef}
        style={styles.cardsWrapper}
        trapFocusLeft
        trapFocusRight
        autoFocus={false}
      >
        <ScrollView
          ref={scrollViewRef}
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.scrollView}
          contentContainerStyle={styles.cardsContainer}
          onScroll={handleScroll}
          scrollEventThrottle={16}
        >
          {loading ? (
            // 显示 8 个骨架屏
            Array.from({ length: 8 }).map((_, index) => (
              <VideoCardSkeleton key={`skeleton-${index}`} />
            ))
          ) : (
            mergedVideos.map((video, index) => (
              <VideoCard
                key={`fav_${video.source}_${video.id}`}
                videoInfo={video}
                from="favorite"
                onFocus={(cardRef) => handleCardFocus(index, cardRef)}
                onNavigateVertical={(direction) => {
                  if (direction === 'up') {
                    resetScroll();
                  } else if (direction === 'down' && !isLast) {
                    resetScroll();
                  }
                }}
                nextFocusUp={titleNodeHandle ?? undefined}
                nextFocusDown={nextSectionTitleNodeHandle ?? undefined}
              />
            ))
          )}
        </ScrollView>
      </TVFocusGuideView>
    </TVFocusGuideView>
  );
}

const styles = StyleSheet.create({
  container: {
    marginBottom: 16,
  },
  titleWrapper: {
    flexDirection: 'row',
  },
  titleContainer: {
    paddingHorizontal: 16,
    paddingVertical: 6,
    marginBottom: 0,
    alignSelf: 'flex-start',
    marginLeft: 19,
    borderRadius: 6,
  },
  title: {
    fontSize: 13,
    fontWeight: '700',
    color: '#ffffff',
  },
  cardsWrapper: {
    flexDirection: 'row',
  },
  scrollView: {
    flexGrow: 0,
  },
  cardsContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
});
