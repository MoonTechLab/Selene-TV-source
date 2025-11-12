import { StyleSheet, View } from 'react-native';
import { useRef } from 'react';
import RecommendSection from '../RecommendSection';

const mockVideos = (prefix: string, count: number = 10) => 
  Array.from({ length: count }, (_, i) => ({
    id: `${prefix}-${i + 1}`,
    title: i % 3 === 1 
      ? `这是一个很长的视频标题需要滚动展示的内容 ${i + 1}` 
      : `示例视频标题 ${i + 1}`,
    coverUrl: 'https://via.placeholder.com/130x195',
  }));

export default function HomeTab() {
  const seriesTitleRef = useRef<any>(null);
  const animeTitleRef = useRef<any>(null);
  const varietyTitleRef = useRef<any>(null);

  return (
    <View style={styles.container}>
      <RecommendSection 
        title="热门电影" 
        videos={mockVideos('movie', 12)}
        isFirst={true}
        nextSectionTitleRef={seriesTitleRef}
      />
      <RecommendSection 
        title="热门剧集" 
        videos={mockVideos('series', 12)}
        titleRef={seriesTitleRef}
        nextSectionTitleRef={animeTitleRef}
      />
      <RecommendSection 
        title="新番放送" 
        videos={mockVideos('anime', 12)}
        titleRef={animeTitleRef}
        nextSectionTitleRef={varietyTitleRef}
      />
      <RecommendSection 
        title="热门综艺" 
        videos={mockVideos('variety', 12)}
        titleRef={varietyTitleRef}
        isLast={true}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingTop: 5,
  },
});
