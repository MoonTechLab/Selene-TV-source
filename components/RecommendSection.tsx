import { StyleSheet, Text, ScrollView, Pressable, TVFocusGuideView, useWindowDimensions, findNodeHandle } from 'react-native';
import { useRef, useState, useEffect } from 'react';
import VideoCard from './VideoCard';
import { useScrollContext } from '@/contexts/ScrollContext';

interface VideoItem {
  id: string;
  title: string;
  coverUrl: string;
}

interface RecommendSectionProps {
  title: string;
  videos: VideoItem[];
  onTitlePress?: () => void;
  isFirst?: boolean;
  isLast?: boolean;
  titleRef?: React.RefObject<any>;
  nextSectionTitleRef?: React.RefObject<any>;
}

export default function RecommendSection({ title, videos, onTitlePress, isFirst = false, isLast = false, titleRef, nextSectionTitleRef }: RecommendSectionProps) {
  const scrollViewRef = useRef<ScrollView>(null);
  const sectionRef = useRef<any>(null);
  const titlePressableRef = useRef<any>(null);
  const cardsWrapperRef = useRef<any>(null);
  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const currentScrollX = useRef(0);
  const { width: screenWidth } = useWindowDimensions();
  const [titleNodeHandle, setTitleNodeHandle] = useState<number | null>(null);
  const [nextSectionTitleNodeHandle, setNextSectionTitleNodeHandle] = useState<number | null>(null);

  const handleSectionFocus = () => {
    console.log(`[${title}] Section focused`);
    if (!mainScrollViewRef?.current) return;
    
    // 使用 setTimeout 确保 ref 已经设置
    setTimeout(() => {
      if (!sectionRef.current) return;
    
      if (isFirst) {
        console.log(`[${title}] Scrolling to top (first section)`);
        mainScrollViewRef.current?.scrollTo({ y: 0, animated: true });
      } else if (isLast) {
        console.log(`[${title}] Scrolling to end (last section)`);
        mainScrollViewRef.current?.scrollToEnd({ animated: true });
      } else {
        // 测量当前区域在主滚动视图中的位置
        sectionRef.current.measureLayout(
          mainScrollViewRef.current as any,
          (_x: number, y: number) => {
            // 预留上下边距（例如 100px）
            const topPadding = 100;
            const scrollToY = Math.max(0, y - topPadding);
            console.log(`[${title}] Scrolling to y: ${scrollToY} (measured y: ${y})`);
            mainScrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
          },
          () => {
            // 测量失败时的回调
            console.log(`[${title}] measureLayout failed`);
          }
        );
      }
    }, 0);
  };

  const handleCardFocus = (index: number) => {
    console.log(`[${title}] Card ${index} focused`);
    if (!scrollViewRef.current) return;
    
    // 卡片宽度 120 + 右边距 20 = 140
    const cardWidth = 140;
    const actualCardWidth = 120;
    const cardPosition = index * cardWidth;
    const horizontalMargin = 100; // 左右预留余量
    const contentPadding = 16; // ScrollView 的 paddingHorizontal
    
    // 计算卡片在视口中的位置
    const cardLeftInViewport = cardPosition - currentScrollX.current;
    const cardRightInViewport = cardLeftInViewport + actualCardWidth;
    
    // 如果卡片右边超出视口或距离右边太近，向右滚动
    if (cardRightInViewport > screenWidth - horizontalMargin) {
      const newScrollX = cardPosition - screenWidth + actualCardWidth + horizontalMargin;
      currentScrollX.current = newScrollX;
      console.log(`[${title}] Scrolling right to x: ${newScrollX}`);
      scrollViewRef.current.scrollTo({ x: newScrollX, animated: true });
    }
    // 如果卡片左边超出视口或距离左边太近，向左滚动
    else if (cardLeftInViewport < horizontalMargin - contentPadding) {
      const newScrollX = Math.max(0, cardPosition - horizontalMargin + contentPadding);
      currentScrollX.current = newScrollX;
      console.log(`[${title}] Scrolling left to x: ${newScrollX}`);
      scrollViewRef.current.scrollTo({ x: newScrollX, animated: true });
    }
  };

  const handleCardsBlur = () => {
    console.log(`[${title}] Cards blurred, resetting scroll`);
    // 当焦点离开卡片区域时，滚动回到最开头
    if (scrollViewRef.current) {
      scrollViewRef.current.scrollTo({ x: 0, animated: true });
      currentScrollX.current = 0;
    }
  };

  const handleTitleFocus = () => {
    console.log(`[${title}] Title focused`);
  };

  const handleTitleBlur = () => {
    console.log(`[${title}] Title blurred`);
  };

  useEffect(() => {
    if (titlePressableRef.current) {
      const handle = findNodeHandle(titlePressableRef.current);
      console.log(`[${title}] Title node handle:`, handle);
      setTitleNodeHandle(handle);
    }
  }, [title]);

  useEffect(() => {
    if (nextSectionTitleRef?.current) {
      const handle = findNodeHandle(nextSectionTitleRef.current);
      console.log(`[${title}] Next section title node handle:`, handle);
      setNextSectionTitleNodeHandle(handle);
    }
  }, [title, nextSectionTitleRef]);

  return (
    <TVFocusGuideView 
      ref={sectionRef}
      style={styles.container}
      onFocus={handleSectionFocus}
      trapFocusUp
      trapFocusDown
      autoFocus={false}
    >
      <TVFocusGuideView 
        style={styles.titleWrapper}
        trapFocusLeft
        trapFocusRight
        autoFocus={false}
        onFocus={handleTitleFocus}
        onBlur={handleTitleBlur}
      >
        <Pressable 
          ref={(ref) => {
            titlePressableRef.current = ref;
            if (titleRef) {
              titleRef.current = ref;
            }
          }}
          style={({ focused }) => [
            styles.titleContainer,
            focused && styles.titleContainerFocused,
          ]}
          onPress={onTitlePress}
        >
          <Text style={styles.title}>{title}</Text>
        </Pressable>
      </TVFocusGuideView>

      <TVFocusGuideView 
        ref={cardsWrapperRef}
        style={styles.cardsWrapper}
        trapFocusLeft
        trapFocusRight
        autoFocus={false}
        onBlur={handleCardsBlur}
      >
        <ScrollView
          ref={scrollViewRef}
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.scrollView}
          contentContainerStyle={styles.cardsContainer}
        >
          {videos.map((video, index) => (
            <VideoCard
              key={video.id}
              title={video.title}
              coverUrl={video.coverUrl}
              onPress={() => console.log('Video pressed:', video.id)}
              onFocus={() => handleCardFocus(index)}
              nextFocusUp={titleNodeHandle ?? undefined}
              nextFocusDown={nextSectionTitleNodeHandle ?? undefined}
            />
          ))}
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
    marginBottom: 4,
    alignSelf: 'flex-start',
    marginLeft: 16,
    borderRadius: 6,
  },
  titleContainerFocused: {
    backgroundColor: 'rgba(255, 255, 255, 0.1)',
  },
  title: {
    fontSize: 16,
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
