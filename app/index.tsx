import { StyleSheet, ScrollView, BackHandler } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { useState, useRef, useEffect } from 'react';
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

const renderTabContent = (tab: TabType) => {
  switch (tab) {
    case 'home':
      return <HomeTab />;
    case 'movie':
      return <MovieTab />;
    case 'series':
      return <SeriesTab />;
    case 'anime':
      return <AnimeTab />;
    case 'variety':
      return <VarietyTab />;
    case 'live':
      return <LiveTab />;
    case 'mine':
      return <MineTab />;
    case 'search':
      return <SearchTab />;
    default:
      return <HomeTab />;
  }
};

export default function HomeScreen() {
  const [activeTab, setActiveTab] = useState<TabType>('home');
  const [focusedTab, setFocusedTab] = useState<TabType | null>(null);
  const timerRef = useRef<NodeJS.Timeout | null>(null);
  const scrollViewRef = useRef<ScrollView>(null);
  const tabSwitcherRef = useRef<any>(null);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      // 滚动到顶部
      scrollViewRef.current?.scrollTo({ y: 0, animated: true });
      
      // 等待滚动动画完成后再聚焦到 TabSwitcher
      setTimeout(() => {
        tabSwitcherRef.current?.focusToHome();
      }, 300);
      
      return true; // 阻止默认的返回行为
    });

    return () => backHandler.remove();
  }, []);

  const handleTabFocus = (tab: TabType | null) => {
    setFocusedTab(tab);
    
    if (tab === null) return;
    
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }
    
    timerRef.current = setTimeout(() => {
      setActiveTab(tab);
    }, 500);
  };

  const handleTabChange = (tab: TabType) => {
    setActiveTab(tab);
  };

  return (
    <LinearGradient
      colors={['#0a0a0a', '#1a1a1a', '#0f0f0f', '#050505']}
      locations={[0, 0.4, 0.7, 1]}
      style={styles.container}
    >
      <ScrollContext.Provider value={{ scrollViewRef }}>
        <ScrollView 
          ref={scrollViewRef}
          style={styles.scrollView}
          showsVerticalScrollIndicator={false}
        >
          <TabSwitcher 
            ref={tabSwitcherRef}
            activeTab={activeTab} 
            focusedTab={focusedTab}
            onTabFocus={handleTabFocus}
            onTabChange={handleTabChange} 
          />

          {renderTabContent(activeTab)}
        </ScrollView>
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
});
