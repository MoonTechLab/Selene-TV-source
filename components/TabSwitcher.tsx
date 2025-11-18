import { StyleSheet, View, Text, Pressable, TVFocusGuideView, Animated, findNodeHandle } from 'react-native';
import { Home, Film, Tv, Cat, Clover, Settings, Search, Radio } from 'lucide-react-native';
import { forwardRef, useImperativeHandle, useRef, useState, useEffect, useCallback } from 'react';

export type TabType = 'home' | 'movie' | 'series' | 'anime' | 'variety' | 'live' | 'mine' | 'search';

interface TabItem {
  key: TabType;
  label?: string;
  icon: any;
}

const tabs: TabItem[] = [
  { key: 'search', icon: Search },
  { key: 'home', label: '首页', icon: Home },
  { key: 'movie', label: '电影', icon: Film },
  { key: 'series', label: '剧集', icon: Tv },
  { key: 'anime', label: '动漫', icon: Cat },
  { key: 'variety', label: '综艺', icon: Clover },
  { key: 'live', label: '直播', icon: Radio },
  { key: 'mine', label: '设置', icon: Settings },
];

interface TabSwitcherProps {
  activeTab: TabType;
  focusedTab: TabType | null;
  isSwitching: boolean;
  onTabFocus: (tab: TabType) => void;
  onTabChange: (tab: TabType) => void;
  firstSectionTitleRef?: React.RefObject<any>;
  movieFirstSelectorRef?: React.RefObject<any>;
  seriesFirstSelectorRef?: React.RefObject<any>;
  animeFirstSelectorRef?: React.RefObject<any>;
  varietyFirstSelectorRef?: React.RefObject<any>;
  onFirstSectionTitleReady?: (nodeHandle: number | null) => void;
  onMovieFirstSelectorReady?: (nodeHandle: number | null) => void;
  onSeriesFirstSelectorReady?: (nodeHandle: number | null) => void;
  onAnimeFirstSelectorReady?: (nodeHandle: number | null) => void;
  onVarietyFirstSelectorReady?: (nodeHandle: number | null) => void;
}

export interface TabSwitcherRef {
  focusToCurrent: () => void;
  updateFirstSectionTitleNodeHandle: (handle: number | null) => void;
  updateMovieFirstSelectorNodeHandle: (handle: number | null) => void;
  updateSeriesFirstSelectorNodeHandle: (handle: number | null) => void;
  updateAnimeFirstSelectorNodeHandle: (handle: number | null) => void;
  updateVarietyFirstSelectorNodeHandle: (handle: number | null) => void;
}

const TabSwitcher = forwardRef<TabSwitcherRef, TabSwitcherProps>(({ 
  activeTab, 
  focusedTab, 
  isSwitching, 
  onTabFocus, 
  onTabChange, 
  firstSectionTitleRef,
  movieFirstSelectorRef,
  seriesFirstSelectorRef,
  animeFirstSelectorRef,
  varietyFirstSelectorRef,
  onFirstSectionTitleReady,
  onMovieFirstSelectorReady,
  onSeriesFirstSelectorReady,
  onAnimeFirstSelectorReady,
  onVarietyFirstSelectorReady
}, ref) => {
  const hasFocus = focusedTab !== null;
  const tabRefs = useRef<Record<TabType, any>>({} as Record<TabType, any>);
  const [shouldFocusCurrent, setShouldFocusCurrent] = useState(true); // 初始为 true，启动时聚焦当前 tab
  const [focusTarget, setFocusTarget] = useState<TabType>(activeTab);
  const [firstSectionTitleNodeHandle, setFirstSectionTitleNodeHandle] = useState<number | null>(null);
  const [movieFirstSelectorNodeHandle, setMovieFirstSelectorNodeHandle] = useState<number | null>(null);
  const [seriesFirstSelectorNodeHandle, setSeriesFirstSelectorNodeHandle] = useState<number | null>(null);
  const [animeFirstSelectorNodeHandle, setAnimeFirstSelectorNodeHandle] = useState<number | null>(null);
  const [varietyFirstSelectorNodeHandle, setVarietyFirstSelectorNodeHandle] = useState<number | null>(null);
  
  // 为每个 tab 创建动画值
  const tabAnimations = useRef(
    tabs.reduce((acc, tab) => {
      acc[tab.key] = new Animated.Value(1);
      return acc;
    }, {} as Record<TabType, Animated.Value>)
  ).current;

  useImperativeHandle(ref, () => ({
    focusToCurrent: () => {
      // 设置焦点目标为当前激活的 tab
      setFocusTarget(activeTab);
      // 触发重新渲染以应用 hasTVPreferredFocus
      setShouldFocusCurrent(true);
      setTimeout(() => {
        setShouldFocusCurrent(false);
      }, 200);
    },
    updateFirstSectionTitleNodeHandle: (handle: number | null) => {
      setFirstSectionTitleNodeHandle(handle);
    },
    updateMovieFirstSelectorNodeHandle: (handle: number | null) => {
      setMovieFirstSelectorNodeHandle(handle);
    },
    updateSeriesFirstSelectorNodeHandle: (handle: number | null) => {
      setSeriesFirstSelectorNodeHandle(handle);
    },
    updateAnimeFirstSelectorNodeHandle: (handle: number | null) => {
      setAnimeFirstSelectorNodeHandle(handle);
    },
    updateVarietyFirstSelectorNodeHandle: (handle: number | null) => {
      setVarietyFirstSelectorNodeHandle(handle);
    },
  }));
  
  // 暴露回调函数给父组件，让子组件在 ref 准备好后调用
  useEffect(() => {
    if (onFirstSectionTitleReady) {
      onFirstSectionTitleReady(firstSectionTitleNodeHandle);
    }
  }, [firstSectionTitleNodeHandle, onFirstSectionTitleReady]);

  useEffect(() => {
    if (onMovieFirstSelectorReady) {
      onMovieFirstSelectorReady(movieFirstSelectorNodeHandle);
    }
  }, [movieFirstSelectorNodeHandle, onMovieFirstSelectorReady]);

  useEffect(() => {
    if (onSeriesFirstSelectorReady) {
      onSeriesFirstSelectorReady(seriesFirstSelectorNodeHandle);
    }
  }, [seriesFirstSelectorNodeHandle, onSeriesFirstSelectorReady]);

  useEffect(() => {
    if (onAnimeFirstSelectorReady) {
      onAnimeFirstSelectorReady(animeFirstSelectorNodeHandle);
    }
  }, [animeFirstSelectorNodeHandle, onAnimeFirstSelectorReady]);

  useEffect(() => {
    if (onVarietyFirstSelectorReady) {
      onVarietyFirstSelectorReady(varietyFirstSelectorNodeHandle);
    }
  }, [varietyFirstSelectorNodeHandle, onVarietyFirstSelectorReady]);

  // 处理焦点变化时的动画
  useEffect(() => {
    tabs.forEach((tab) => {
      const isFocused = focusedTab === tab.key;
      Animated.spring(tabAnimations[tab.key], {
        toValue: isFocused ? 1.05 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedTab]);

  // 根据当前 activeTab 获取对应的 nextFocusDown
  const getNextFocusDown = useCallback(() => {
    switch (activeTab) {
      case 'home':
        return firstSectionTitleNodeHandle;
      case 'movie':
        return movieFirstSelectorNodeHandle;
      case 'series':
        return seriesFirstSelectorNodeHandle;
      case 'anime':
        return animeFirstSelectorNodeHandle;
      case 'variety':
        return varietyFirstSelectorNodeHandle;
      default:
        return undefined;
    }
  }, [activeTab, firstSectionTitleNodeHandle, movieFirstSelectorNodeHandle, seriesFirstSelectorNodeHandle, animeFirstSelectorNodeHandle, varietyFirstSelectorNodeHandle]);
  
  return (
    <View style={styles.navContainer}>
      <TVFocusGuideView 
        style={styles.navWrapper} 
        autoFocus 
        trapFocusLeft 
        trapFocusRight
        trapFocusUp={isSwitching}
        trapFocusDown={isSwitching}
      >
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.key;
          
          return (
            <Animated.View
              key={tab.key}
              style={{
                transform: [{ scale: tabAnimations[tab.key] }],
              }}
            >
              <Pressable
                ref={(el) => {
                  tabRefs.current[tab.key] = el;
                }}
                style={({ focused }) => [
                  styles.tabItem,
                  !tab.label && styles.tabItemIconOnly,
                  isActive && !hasFocus && styles.tabItemActive,
                  focused && styles.tabItemFocused,
                ]}
                onFocus={() => onTabFocus(tab.key)}
                onPress={() => {
                  // 只有在不是当前激活标签时才触发切换
                  if (tab.key !== activeTab) {
                    onTabChange(tab.key);
                  }
                }}
                onBlur={() => onTabFocus(null as any)}
                hasTVPreferredFocus={tab.key === focusTarget && shouldFocusCurrent}
                nextFocusDown={isSwitching ? tabRefs.current[tab.key] : (tab.key === activeTab ? getNextFocusDown() : undefined)}
              >
                {({ focused }) => (
                  <>
                    <Icon
                      size={12}
                      color={focused ? '#0a0a0a' : '#ffffff'}
                      strokeWidth={2}
                    />
                    {tab.label && (
                      <Text style={[styles.tabText, focused && styles.tabTextFocused]}>
                        {tab.label}
                      </Text>
                    )}
                  </>
                )}
              </Pressable>
            </Animated.View>
          );
        })}
      </TVFocusGuideView>
    </View>
  );
});

TabSwitcher.displayName = 'TabSwitcher';

export default TabSwitcher;

const styles = StyleSheet.create({
  navContainer: {
    paddingTop: 13,
    paddingBottom: 7,
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  navWrapper: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderRadius: 18,
    padding: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 12,
    elevation: 8,
  },
  tabItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    height: 26,
    paddingHorizontal: 10.8,
    borderRadius: 13,
    gap: 4.5,
  },
  tabItemIconOnly: {
    paddingHorizontal: 8,
  },
  tabItemActive: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
  },
  tabItemFocused: {
    backgroundColor: '#ffffff',
  },
  tabText: {
    fontSize: 11,
    fontWeight: '600',
    color: '#ffffff',
  },
  tabTextFocused: {
    color: '#0a0a0a',
  },
});
