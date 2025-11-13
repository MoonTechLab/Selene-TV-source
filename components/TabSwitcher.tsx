import { StyleSheet, View, Text, Pressable, TVFocusGuideView, Animated, findNodeHandle } from 'react-native';
import { Home, Film, Tv, Cat, Clover, User, Search, Radio } from 'lucide-react-native';
import { forwardRef, useImperativeHandle, useRef, useState, useEffect } from 'react';

export type TabType = 'home' | 'movie' | 'series' | 'anime' | 'variety' | 'live' | 'mine' | 'search';

interface TabItem {
  key: TabType;
  label?: string;
  icon: any;
}

const tabs: TabItem[] = [
  { key: 'home', label: '首页', icon: Home },
  { key: 'movie', label: '电影', icon: Film },
  { key: 'series', label: '剧集', icon: Tv },
  { key: 'anime', label: '动漫', icon: Cat },
  { key: 'variety', label: '综艺', icon: Clover },
  { key: 'live', label: '直播', icon: Radio },
  { key: 'mine', label: '我的', icon: User },
  { key: 'search', icon: Search },
];

interface TabSwitcherProps {
  activeTab: TabType;
  focusedTab: TabType | null;
  isSwitching: boolean;
  onTabFocus: (tab: TabType) => void;
  onTabChange: (tab: TabType) => void;
  firstSectionTitleRef?: React.RefObject<any>;
}

export interface TabSwitcherRef {
  focusToCurrent: () => void;
}

const TabSwitcher = forwardRef<TabSwitcherRef, TabSwitcherProps>(({ activeTab, focusedTab, isSwitching, onTabFocus, onTabChange, firstSectionTitleRef }, ref) => {
  const hasFocus = focusedTab !== null;
  const tabRefs = useRef<Record<TabType, any>>({} as Record<TabType, any>);
  const [shouldFocusCurrent, setShouldFocusCurrent] = useState(true); // 初始为 true，启动时聚焦当前 tab
  const [focusTarget, setFocusTarget] = useState<TabType>(activeTab);
  const [firstSectionNodeHandle, setFirstSectionNodeHandle] = useState<number | null>(null);
  
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
  }));
  
  // 处理焦点变化时的动画
  useEffect(() => {
    tabs.forEach((tab) => {
      const isFocused = focusedTab === tab.key;
      Animated.timing(tabAnimations[tab.key], {
        toValue: isFocused ? 1.05 : 1,
        duration: 300,
        useNativeDriver: true,
      }).start();
    });
  }, [focusedTab]);

  // 监听 activeTab 变化，更新节点句柄
  useEffect(() => {
    // 使用 setTimeout 确保 HomeTab 已经渲染完成
    const timer = setTimeout(() => {
      if (activeTab === 'home' && firstSectionTitleRef?.current) {
        const handle = findNodeHandle(firstSectionTitleRef.current);
        setFirstSectionNodeHandle(handle);
      } else {
        setFirstSectionNodeHandle(null);
      }
    }, 150); // 等待动画完成后再获取节点句柄

    return () => clearTimeout(timer);
  }, [activeTab]);
  
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
                onPress={() => onTabChange(tab.key)}
                onBlur={() => onTabFocus(null as any)}
                hasTVPreferredFocus={tab.key === focusTarget && shouldFocusCurrent}
                nextFocusDown={
                  isSwitching 
                    ? tabRefs.current[tab.key] 
                    : firstSectionNodeHandle ?? undefined
                }
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
    height: 28,
    paddingHorizontal: 12,
    borderRadius: 14,
    gap: 5,
  },
  tabItemIconOnly: {
    paddingHorizontal: 9,
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
