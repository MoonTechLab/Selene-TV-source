import { StyleSheet, View, Text, TextInput, Pressable, Animated, TVFocusGuideView } from 'react-native';
import { useState, useEffect, useRef } from 'react';
import { Search } from 'lucide-react-native';
import userDataCacheService from '@/services/userDataCacheService';

export default function SearchTab() {
  const [searchQuery, setSearchQuery] = useState('');
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  const [focusedItem, setFocusedItem] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const searchInputRef = useRef<TextInput>(null);

  // 动画值
  const itemAnimations = useRef<Record<string, Animated.Value>>({}).current;

  useEffect(() => {
    loadSearchHistory();
  }, []);

  const loadSearchHistory = async () => {
    setLoading(true);
    const result = await userDataCacheService.getSearchHistory();
    if (result.success && result.data) {
      setSearchHistory(result.data);
    }
    setLoading(false);
  };

  const getItemAnimation = (key: string) => {
    if (!itemAnimations[key]) {
      itemAnimations[key] = new Animated.Value(1);
    }
    return itemAnimations[key];
  };

  // 处理聚焦动画
  useEffect(() => {
    Object.keys(itemAnimations).forEach((key) => {
      const isFocused = focusedItem === key;
      Animated.spring(itemAnimations[key], {
        toValue: isFocused ? 1.02 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedItem, itemAnimations]);

  const handleSearch = async () => {
    if (!searchQuery.trim()) return;
    
    // 添加到搜索历史
    await userDataCacheService.addSearchHistory(searchQuery.trim());
    
    // 重新加载搜索历史
    await loadSearchHistory();
    
    // TODO: 执行搜索操作
    console.log('搜索:', searchQuery);
  };

  const handleHistoryItemPress = (query: string) => {
    setSearchQuery(query);
  };

  const isSearchDisabled = !searchQuery.trim();

  return (
    <View style={styles.container}>
      {/* 搜索栏 */}
      <TVFocusGuideView style={styles.searchContainer} trapFocusLeft trapFocusRight>
        <View style={styles.searchInputWrapper}>
          <Search
            size={14}
            color="#999"
            strokeWidth={2}
            style={styles.searchIcon}
          />
          <TextInput
            ref={searchInputRef}
            style={styles.searchInput}
            value={searchQuery}
            onChangeText={setSearchQuery}
            placeholder="搜索影视剧"
            placeholderTextColor="#666"
            onSubmitEditing={handleSearch}
          />
        </View>
        <Animated.View style={{ transform: [{ scale: getItemAnimation('search-button') }] }}>
          <Pressable
            style={[
              styles.searchButton,
              isSearchDisabled && styles.searchButtonDisabled,
              isSearchDisabled && focusedItem === 'search-button' && styles.searchButtonDisabledFocused,
              !isSearchDisabled && focusedItem === 'search-button' && styles.searchButtonFocused,
            ]}
            onPress={handleSearch}
            onFocus={() => setFocusedItem('search-button')}
            onBlur={() => setFocusedItem(null)}
            disabled={isSearchDisabled}
          >
            <Text
              style={[
                styles.searchButtonText,
                isSearchDisabled && styles.searchButtonTextDisabled,
                isSearchDisabled && focusedItem === 'search-button' && styles.searchButtonTextDisabledFocused,
                !isSearchDisabled && focusedItem === 'search-button' && styles.searchButtonTextFocused,
              ]}
            >
              搜索
            </Text>
          </Pressable>
        </Animated.View>
      </TVFocusGuideView>

      {/* 搜索历史 */}
      <View style={styles.historyContainer}>
        <Text style={styles.historyTitle}>搜索历史</Text>

        {loading ? (
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>加载中...</Text>
          </View>
        ) : searchHistory.length === 0 ? (
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>暂无搜索历史</Text>
          </View>
        ) : (
          <View style={styles.historyListWrapper}>
            {searchHistory.map((item, index) => {
              const itemKey = `history-${index}`;
              const animation = getItemAnimation(itemKey);

              return (
                <Animated.View key={`${item}-${index}`} style={{ transform: [{ scale: animation }] }}>
                  <Pressable
                    style={[
                      styles.historyItem,
                      focusedItem === itemKey && styles.historyItemFocused,
                    ]}
                    onPress={() => handleHistoryItemPress(item)}
                    onFocus={() => setFocusedItem(itemKey)}
                    onBlur={() => setFocusedItem(null)}
                  >
                    <Text
                      style={[
                        styles.historyItemText,
                        focusedItem === itemKey && styles.historyItemTextFocused,
                      ]}
                      numberOfLines={1}
                    >
                      {item}
                    </Text>
                  </Pressable>
                </Animated.View>
              );
            })}
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 30,
  },
  searchContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 24,
    alignSelf: 'center',
  },
  searchInputWrapper: {
    width: 480,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderRadius: 16,
    paddingHorizontal: 16,
    height: 32,
    gap: 10,
  },
  searchIcon: {
    flexShrink: 0,
  },
  searchInput: {
    flex: 1,
    fontSize: 12,
    color: '#ffffff',
    padding: 0,
  },
  searchButton: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    paddingHorizontal: 18,
    height: 32,
    borderRadius: 16,
  },
  searchButtonDisabled: {
    backgroundColor: 'rgba(255, 255, 255, 0.05)',
  },
  searchButtonDisabledFocused: {
    backgroundColor: 'rgba(255, 255, 255, 0.15)',
  },
  searchButtonFocused: {
    backgroundColor: '#ffffff',
  },
  searchButtonText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#ffffff',
  },
  searchButtonTextDisabled: {
    color: '#666',
  },
  searchButtonTextDisabledFocused: {
    color: '#999',
  },
  searchButtonTextFocused: {
    color: '#0a0a0a',
  },
  historyContainer: {
    flex: 1,
    paddingHorizontal: 40,
  },
  historyTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  historyListWrapper: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    paddingBottom: 20,
  },
  historyItem: {
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderRadius: 14,
    alignSelf: 'flex-start',
  },
  historyItemFocused: {
    backgroundColor: '#ffffff',
  },
  historyItemText: {
    fontSize: 12,
    color: '#cccccc',
    fontWeight: '500',
  },
  historyItemTextFocused: {
    color: '#0a0a0a',
  },
  emptyContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: 80,
  },
  emptyText: {
    fontSize: 14,
    color: '#666',
  },
});
