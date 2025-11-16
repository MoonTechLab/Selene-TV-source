import { StyleSheet, ScrollView, BackHandler, Animated, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { useState, useRef, useEffect, useCallback } from 'react';
import TabSwitcher, { TabType } from '@/components/TabSwitcher';
import HomeTab from '@/components/tabs/HomeTab';
import MovieTab from '@/components/tabs/MovieTab';
import SeriesTab from '@/components/tabs/SeriesTab';
import AnimeTab from '@/components/tabs/AnimeTab';
import VarietyTab from '@/components/tabs/VarietyTab';
import LiveTab from '@/components/tabs/LiveTab';
import MineTab from '@/components/tabs/MineTab';
import SearchTab from '@/components/tabs/SearchTab';
import { ScrollContext } from '@/contexts/ScrollContext';

const TAB_ORDER: TabType[] = ['home', 'movie', 'series', 'anime', 'variety', 'live', 'mine', 'search'];

const renderTabContent = (
  tab: TabType, 
  firstSectionTitleRef?: React.RefObject<any>,
  movieFirstSelectorRef?: React.RefObject<any>,
  seriesFirstSelectorRef?: React.RefObject<any>,
  animeFirstSelectorRef?: React.RefObject<any>,
  varietyFirstSelectorRef?: React.RefObject<any>
) => {
  switch (tab) {
    case 'home':
      return <HomeTab firstSectionTitleRef={firstSectionTitleRef} />;
    case 'movie':
      return <MovieTab firstSelectorRef={movieFirstSelectorRef} />;
    case 'series':
      return <SeriesTab firstSelectorRef={seriesFirstSelectorRef} />;
    case 'anime':
      return <AnimeTab firstSelectorRef={animeFirstSelectorRef} />;
    case 'variety':
      return <VarietyTab firstSelectorRef={varietyFirstSelectorRef} />;
    case 'live':
      return <LiveTab />;
    case 'mine':
      return <MineTab />;
    case 'search':
      return <SearchTab />;
    default:
      return <HomeTab firstSectionTitleRef={firstSectionTitleRef} />;
  }
};

export default function HomeScreen() {
  const [activeTab, setActiveTab] = useState<TabType>('home');
  const [focusedTab, setFocusedTab] = useState<TabType | null>(null);
  const [isSwitching, setIsSwitching] = useState(false);
  const timerRef = useRef<number | null>(null);
  const scrollViewRef = useRef<ScrollView>(null);
  const measureContainerRef = useRef<View>(null);
  const tabSwitcherRef = useRef<any>(null);
  const firstSectionTitleRef = useRef<any>(null);
  const movieFirstSelectorRef = useRef<any>(null);
  const seriesFirstSelectorRef = useRef<any>(null);
  const animeFirstSelectorRef = useRef<any>(null);
  const varietyFirstSelectorRef = useRef<any>(null);
  
  // 用于在全局测量容器中渲染测量元素
  const [measureElement, setMeasureElement] = useState<React.ReactNode>(null);
  
  const renderMeasureElement = useCallback((element: React.ReactNode) => {
    setMeasureElement(element);
  }, []);
  
  const clearMeasureElement = useCallback(() => {
    setMeasureElement(null);
  }, []);
  
  // 动画值
  const translateX = useRef(new Animated.Value(0)).current;
  const opacity = useRef(new Animated.Value(1)).current;
  const previousTabRef = useRef<TabType>(activeTab);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      // 滚动到顶部
      scrollViewRef.current?.scrollTo({ y: 0, animated: true });
      
      // 使用 requestAnimationFrame 确保在下一帧执行，无需固定延时
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          tabSwitcherRef.current?.focusToCurrent();
        });
      });
      
      return true; // 阻止默认的返回行为
    });

    return () => backHandler.remove();
  }, []);

  const animateTabChange = (newTab: TabType) => {
    const currentIndex = TAB_ORDER.indexOf(previousTabRef.current);
    const newIndex = TAB_ORDER.indexOf(newTab);
    const direction = newIndex > currentIndex ? 1 : -1; // 1: 向右, -1: 向左
    
    // 第一阶段：淡出并移动
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 0,
        duration: 100,
        useNativeDriver: true,
      }),
      Animated.timing(translateX, {
        toValue: direction * -50, // 向左移动 50 或向右移动 -50
        duration: 100,
        useNativeDriver: true,
      }),
    ]).start(() => {
      // 先重置位置到另一侧（在内容切换之前）
      translateX.setValue(direction * 50);
      
      // 然后切换内容
      setActiveTab(newTab);
      previousTabRef.current = newTab;
      
      // 使用 requestAnimationFrame 确保内容切换后再开始第二阶段动画
      requestAnimationFrame(() => {
        // 第二阶段：从另一侧淡入并移动到中心
        Animated.parallel([
          Animated.timing(opacity, {
            toValue: 1,
            duration: 100,
            useNativeDriver: true,
          }),
          Animated.timing(translateX, {
            toValue: 0,
            duration: 100,
            useNativeDriver: true,
          }),
        ]).start();
      });
    });
  };

  const handleTabFocus = (tab: TabType | null) => {
    setFocusedTab(tab);
    
    if (tab === null) {
      setIsSwitching(false);
      return;
    }
    
    // 如果聚焦的是当前已激活的 tab，不触发切换
    if (tab === activeTab) {
      setIsSwitching(false);
      return;
    }
    
    // 清除之前的定时器
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }
    
    // 标记正在切换
    setIsSwitching(true);
    
    timerRef.current = setTimeout(() => {
      animateTabChange(tab);
      setIsSwitching(false);
    }, 500);
  };

  const handleTabChange = (tab: TabType) => {
    // 如果点按的是当前已激活的 tab，不响应
    if (tab === activeTab) {
      return;
    }
    
    // 清除定时器
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    animateTabChange(tab);
    setIsSwitching(false);
  };

  return (
    <LinearGradient
      colors={['#0a0a0a', '#1a1a1a', '#0f0f0f', '#050505']}
      locations={[0, 0.4, 0.7, 1]}
      style={styles.container}
    >
      <ScrollContext.Provider value={{ scrollViewRef, measureContainerRef, renderMeasureElement, clearMeasureElement }}>
        <ScrollView 
          ref={scrollViewRef}
          style={styles.scrollView}
          showsVerticalScrollIndicator={false}
          removeClippedSubviews={false}
          scrollEventThrottle={16}
          nestedScrollEnabled={false}
          overScrollMode="never"
          bounces={false}
        >
          <TabSwitcher 
            ref={tabSwitcherRef}
            activeTab={activeTab} 
            focusedTab={focusedTab}
            isSwitching={isSwitching}
            onTabFocus={handleTabFocus}
            onTabChange={handleTabChange}
            firstSectionTitleRef={firstSectionTitleRef}
            movieFirstSelectorRef={movieFirstSelectorRef}
            seriesFirstSelectorRef={seriesFirstSelectorRef}
            animeFirstSelectorRef={animeFirstSelectorRef}
            varietyFirstSelectorRef={varietyFirstSelectorRef}
          />

          <Animated.View
            style={{
              opacity,
              transform: [{ translateX }],
            }}
          >
            {renderTabContent(activeTab, firstSectionTitleRef, movieFirstSelectorRef, seriesFirstSelectorRef, animeFirstSelectorRef, varietyFirstSelectorRef)}
          </Animated.View>
        </ScrollView>
        
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
  scrollView: {
    flex: 1,
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
});
