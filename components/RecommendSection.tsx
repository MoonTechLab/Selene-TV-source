import { StyleSheet, Text, ScrollView, Pressable, TVFocusGuideView, useWindowDimensions, findNodeHandle, BackHandler, Animated } from 'react-native';
import { useRef, useState, useEffect } from 'react';
import VideoCard from './VideoCard';
import VideoCardSkeleton from './VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import { VideoInfo } from '@/types/entities';

interface RecommendSectionProps {
  title: string;
  videos: VideoInfo[];
  from?: string;
  onTitlePress?: () => void;
  titleRef?: React.RefObject<any>;
  nextSectionTitleRef?: React.RefObject<any>;
  isLast?: boolean;
  loading?: boolean;
}

export default function RecommendSection({ title, videos, from, onTitlePress, titleRef, nextSectionTitleRef, isLast = false, loading = false }: RecommendSectionProps) {
  const scrollViewRef = useRef<ScrollView>(null);
  const sectionRef = useRef<any>(null);
  const titlePressableRef = useRef<any>(null);
  const cardsWrapperRef = useRef<any>(null);
  const { scrollViewRef: mainScrollViewRef, isPageFocusedRef } = useScrollContext();
  const currentScrollX = useRef(0);
  const hasScrolledVertically = useRef(false);
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
  const [titleNodeHandle, setTitleNodeHandle] = useState<number | null>(null);
  const [nextSectionTitleNodeHandle, setNextSectionTitleNodeHandle] = useState<number | null>(null);
  const titleFocusAnim = useRef(new Animated.Value(0)).current;

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

    if (!mainScrollViewRef?.current || !titlePressableRef.current || !isPageFocusedRef?.current) return;

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
    // 但如果页面失去焦点，不执行滚动（避免导航时的焦点转移触发滚动）
    if (!hasScrolledVertically.current && mainScrollViewRef?.current && cardRef && isPageFocusedRef?.current) {
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
            videos.map((video, index) => (
              <VideoCard
                key={`${video.source}_${video.id}`}
                videoInfo={video}
                from={from}
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
    marginBottom: 24,
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
    paddingVertical: 8,
  },
});
