import { StyleSheet, View, Text, TextInput, Pressable, Animated, TVFocusGuideView, Dimensions, useWindowDimensions, findNodeHandle } from 'react-native';
import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Search } from 'lucide-react-native';
import userDataCacheService from '@/services/userDataCacheService';
import SearchService, { SearchProgress } from '@/services/searchService';
import { SearchResult } from '@/types/entities';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';

const COLUMNS = 7;
const CARD_WIDTH = 100;
const CARD_MARGIN_RIGHT = 20;
const RENDER_BUFFER = 3;
const ROW_GAP = 20;
const ROW_HEIGHT = 150 + ROW_GAP;

// 计算布局参数
const calculateLayout = () => {
  const screenWidth = Dimensions.get('window').width;
  const totalCardWidth = CARD_WIDTH * COLUMNS;
  const availableSpace = screenWidth - totalCardWidth;
  const horizontalPadding = Math.floor(availableSpace * 0.15);
  const totalGapSpace = availableSpace * 0.7;
  const cardGap = Math.floor(totalGapSpace / (COLUMNS - 1));
  
  return {
    horizontalPadding: Math.max(20, horizontalPadding),
    cardGap: Math.max(20, cardGap),
  };
};

export default function SearchTab() {
  const [searchQuery, setSearchQuery] = useState('');
  const [searchHistory, setSearchHistory] = useState<string[]>([]);
  const [focusedItem, setFocusedItem] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const searchInputRef = useRef<TextInput>(null);

  // 搜索结果相关状态
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searchProgress, setSearchProgress] = useState<SearchProgress | null>(null);
  const [hasReceivedStart, setHasReceivedStart] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isSearching, setIsSearching] = useState(false);

  // 虚拟化相关
  const [focusedRowIndex, setFocusedRowIndex] = useState(0);
  const currentRowRef = useRef<number>(-1);
  const rowRefs = useRef<Map<number, any>>(new Map());
  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const { height: screenHeight } = useWindowDimensions();

  // 动画值
  const itemAnimations = useRef<Record<string, Animated.Value>>({}).current;



  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  useEffect(() => {
    loadSearchHistory();
    
    // 设置搜索事件监听器
    const unsubscribeResults = SearchService.addEventListener<SearchResult[]>('results', (results) => {
      if (results.length > 0) {
        setSearchResults((prev) => [...prev, ...results]);
      }
    });

    const unsubscribeProgress = SearchService.addEventListener<SearchProgress>('progress', (progress) => {
      setSearchProgress(progress);
      setHasReceivedStart(true);
      
      if (progress.isComplete) {
        setIsSearching(false);
      }
    });

    const unsubscribeError = SearchService.addEventListener<string>('error', (error) => {
      // 检查是否是连接关闭错误，如果是则忽略
      const errorString = error.toLowerCase();
      if (
        errorString.includes('connection closed') ||
        errorString.includes('clientexception') ||
        errorString.includes('connection terminated')
      ) {
        return;
      }
      setSearchError(error);
    });

    const unsubscribeComplete = SearchService.addEventListener('complete', () => {
      setIsSearching(false);
    });

    return () => {
      unsubscribeResults();
      unsubscribeProgress();
      unsubscribeError();
      unsubscribeComplete();
    };
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
    
    // 重置搜索状态
    setSearchResults([]);
    setSearchProgress(null);
    setHasReceivedStart(false);
    setSearchError(null);
    setIsSearching(true);
    setFocusedRowIndex(0);
    currentRowRef.current = -1;
    
    // 执行搜索
    try {
      await SearchService.startSearch(searchQuery.trim());
    } catch (error) {
      setSearchError(error instanceof Error ? error.message : String(error));
      setIsSearching(false);
    }
  };

  const handleHistoryItemPress = async (query: string) => {
    setSearchQuery(query);
    
    // 自动聚焦到搜索按钮
    setFocusedItem('search-button');
    
    // 自动发起搜索
    // 添加到搜索历史
    await userDataCacheService.addSearchHistory(query.trim());
    
    // 重新加载搜索历史
    await loadSearchHistory();
    
    // 重置搜索状态
    setSearchResults([]);
    setSearchProgress(null);
    setHasReceivedStart(false);
    setSearchError(null);
    setIsSearching(true);
    setFocusedRowIndex(0);
    currentRowRef.current = -1;
    
    // 执行搜索
    try {
      await SearchService.startSearch(query.trim());
    } catch (error) {
      setSearchError(error instanceof Error ? error.message : String(error));
      setIsSearching(false);
    }
  };

  const isSearchDisabled = !searchQuery.trim();

  // 获取进度文本
  const getProgressText = () => {
    if (searchProgress) {
      return `${searchProgress.completedSources}/${searchProgress.totalSources}`;
    }
    return '0/0';
  };

  // 将搜索结果分组为行
  const rows = useMemo(() => {
    const result = [];
    for (let i = 0; i < searchResults.length; i += COLUMNS) {
      result.push(searchResults.slice(i, i + COLUMNS));
    }
    return result;
  }, [searchResults]);

  // 计算需要渲染的行范围（虚拟化）
  const visibleRows = useMemo(() => {
    const startRow = Math.max(0, focusedRowIndex - RENDER_BUFFER);
    const endRow = Math.min(rows.length, focusedRowIndex + RENDER_BUFFER + 1);
    
    return { startRow, endRow };
  }, [focusedRowIndex, rows.length]);

  const handleCardFocus = useCallback((rowIndex: number) => {
    setFocusedRowIndex(rowIndex);

    if (currentRowRef.current !== rowIndex && mainScrollViewRef?.current) {
      currentRowRef.current = rowIndex;
      
      const rowRef = rowRefs.current.get(rowIndex);
      if (rowRef) {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            rowRef.measureLayout(
              mainScrollViewRef.current as any,
              (_x: number, y: number, _width: number, height: number) => {
                const scrollToY = Math.max(0, y - (screenHeight / 2) + (height / 2));
                mainScrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
              },
              () => {}
            );
          });
        });
      }
    }
  }, [mainScrollViewRef, screenHeight]);

  // 计算底部 padding
  const bottomPadding = Math.max(0, (screenHeight / 2) - 75);

  return (
    <View style={[styles.container, { paddingHorizontal: layout.horizontalPadding, paddingBottom: bottomPadding }]}>
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

      {/* 搜索历史或搜索结果 */}
      <View style={styles.contentContainer}>
        {hasReceivedStart ? (
          <>
            {/* 搜索结果标题 */}
            <View style={styles.resultHeader}>
              <Text style={styles.resultTitle}>搜索结果</Text>
              <Text style={styles.progressText}>{getProgressText()}</Text>
            </View>

            {/* 搜索结果网格 */}
            {searchError && (
              <Text style={styles.errorText}>{searchError}</Text>
            )}
            
            {searchResults.length === 0 && !isSearching ? (
              <View style={styles.emptyContainer}>
                <Text style={styles.emptyText}>暂无搜索结果</Text>
              </View>
            ) : (
              <View style={styles.resultsGrid}>
                {rows.map((row, rowIndex) => {
                  const isVisible = rowIndex >= visibleRows.startRow && rowIndex < visibleRows.endRow;
                  
                  if (!isVisible) {
                    return <View key={`row-${rowIndex}`} style={styles.rowPlaceholder} />;
                  }

                  const isLastRow = rowIndex === rows.length - 1;
                  const shouldShowSkeletons = isLastRow && isSearching && row.length < COLUMNS;
                  const skeletonCount = shouldShowSkeletons ? COLUMNS - row.length : 0;

                  return (
                    <View 
                      key={`row-${rowIndex}`} 
                      style={styles.row}
                      ref={(ref) => {
                        if (ref) {
                          rowRefs.current.set(rowIndex, ref);
                        } else {
                          rowRefs.current.delete(rowIndex);
                        }
                      }}
                    >
                      {row.map((result, colIndex) => (
                        <View 
                          key={`${result.source}+${result.id}`}
                          style={[
                            styles.cardWrapper,
                            colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                          ]}
                        >
                          <VideoCard
                            videoInfo={{
                              id: result.id,
                              source: result.source,
                              title: result.title,
                              sourceName: result.sourceName,
                              year: result.year,
                              cover: result.poster,
                              index: 1,
                              totalEpisodes: result.episodes.length,
                              playTime: 0,
                              totalTime: 0,
                              saveTime: Date.now(),
                              searchTitle: result.title,
                            }}
                            from='search'
                            onPress={() => console.log('Pressed:', result.title)}
                            onFocus={() => handleCardFocus(rowIndex)}
                          />
                        </View>
                      ))}
                      {shouldShowSkeletons && Array.from({ length: skeletonCount }).map((_, index) => (
                        <View 
                          key={`skeleton-fill-${index}`}
                          style={[
                            styles.cardWrapper,
                            row.length + index < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                          ]}
                        >
                          <VideoCardSkeleton />
                        </View>
                      ))}
                    </View>
                  );
                })}
                
                {isSearching && (rows.length === 0 || rows[rows.length - 1].length === COLUMNS) && (
                  <>
                    {Array.from({ length: 2 }).map((_, rowIdx) => (
                      <View key={`skeleton-row-${rowIdx}`} style={styles.row}>
                        {Array.from({ length: COLUMNS }).map((_, index) => (
                          <View 
                            key={`skeleton-${rowIdx}-${index}`}
                            style={[
                              styles.cardWrapper,
                              index < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                            ]}
                          >
                            <VideoCardSkeleton />
                          </View>
                        ))}
                      </View>
                    ))}
                  </>
                )}
              </View>
            )}
          </>
        ) : (
          <>
            {/* 搜索历史 */}
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
          </>
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
  contentContainer: {
    flex: 1,
  },
  resultHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  resultTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: '#ffffff',
  },
  progressText: {
    fontSize: 14,
    fontWeight: '400',
    color: '#b0b0b0',
  },
  resultsGrid: {
    gap: ROW_GAP,
  },
  row: {
    flexDirection: 'row',
  },
  rowPlaceholder: {
    height: ROW_HEIGHT,
  },
  cardWrapper: {
    // 用于抵消 VideoCard 自带的 marginRight
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
  errorText: {
    fontSize: 14,
    color: '#ff6b6b',
    textAlign: 'center',
    marginBottom: 20,
  },
});
