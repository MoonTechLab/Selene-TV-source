import { StyleSheet, Text, ScrollView, Pressable, TVFocusGuideView, useWindowDimensions, findNodeHandle, BackHandler, Animated } from 'react-native';
import { useRef, useState, useEffect } from 'react';
import VideoCard from './VideoCard';
import VideoCardSkeleton from './VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import { VideoInfo } from '@/types/entities';

interface PlayRecordSectionProps {
  title: string;
  videos: VideoInfo[];
  onTitlePress?: () => void;
  titleRef?: React.RefObject<any>;
  nextSectionTitleRef?: React.RefObject<any>;
  isLast?: boolean;
  loading?: boolean;
}

export default function PlayRecordSection({ title, videos, onTitlePress, titleRef, nextSectionTitleRef, isLast = false, loading = false }: PlayRecordSectionProps) {
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

    // 卡片宽度 100 + 右边距 20 = 120
    const cardWidth = 120;
    const actualCardWidth = 100;
    const cardPosition = index * cardWidth;
    const horizontalMargin = 160; // 左右预留余量
    const contentPadding = 16; // ScrollView 的 paddingHorizontal

    // 计算卡片在视口中的位置
    const cardLeftInViewport = cardPosition - currentScrollX.current;
    const cardRightInViewport = cardLeftInViewport + actualCardWidth;

    // 如果卡片右边超出视口或距离右边太近，向右滚动
    if (cardRightInViewport > screenWidth - horizontalMargin) {
      const newScrollX = cardPosition - screenWidth + actualCardWidth + horizontalMargin;
      currentScrollX.current = newScrollX;
      scrollViewRef.current.scrollTo({ x: newScrollX, animated: true });
    }
    // 如果卡片左边超出视口或距离左边太近，向左滚动
    else if (cardLeftInViewport < horizontalMargin - contentPadding) {
      const newScrollX = Math.max(0, cardPosition - horizontalMargin + contentPadding);
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
            videos.map((video, index) => (
              <VideoCard
                key={`pr_${video.source}_${video.id}`}
                videoInfo={video}
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
    marginLeft: 16,
    borderRadius: 6,
  },
  title: {
    fontSize: 14,
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
