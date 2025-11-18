import { StyleSheet, View, Text, TextInput, Pressable, Animated, TVFocusGuideView, Dimensions, useWindowDimensions, findNodeHandle } from 'react-native';
import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Search, ArrowUpDown, ArrowUp, ArrowDown } from 'lucide-react-native';
import userDataCacheService from '@/services/userDataCacheService';
import SearchService, { SearchProgress } from '@/services/searchService';
import { SearchResult } from '@/types/entities';
import { 
  AggregatedSearchResult, 
  createAggregatedSearchResult, 
  addResultToAggregated,
  aggregatedSearchResultToVideoInfo,
  generateAggregationKey
} from '@/types/aggregatedSearchResult';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import TransparentFilterSelector from '../TransparentFilterSelector';
import FilterDialog from '../FilterDialog';

const COLUMNS = 8;
const CARD_WIDTH = 90;
const CARD_MARGIN_RIGHT = 20;
const RENDER_BUFFER = 3;
const ROW_GAP = 20;
const ROW_HEIGHT = 135 + ROW_GAP;

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
  const searchButtonRef = useRef<any>(null);
  const [searchButtonHandle, setSearchButtonHandle] = useState<number | null>(null);
  const [searchInputHandle, setSearchInputHandle] = useState<number | null>(null);

  // 搜索结果相关状态
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searchProgress, setSearchProgress] = useState<SearchProgress | null>(null);
  const [hasReceivedStart, setHasReceivedStart] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isSearching, setIsSearching] = useState(false);

  // 聚合结果映射，key为聚合键，value为聚合结果
  const [aggregatedResults, setAggregatedResults] = useState<Map<string, AggregatedSearchResult>>(new Map());
  // 按添加顺序排列的聚合键列表
  const [orderedKeys, setOrderedKeys] = useState<string[]>([]);

  // 筛选相关状态
  const [selectedSource, setSelectedSource] = useState<string>('全部');
  const [selectedTitle, setSelectedTitle] = useState<string>('全部');
  const [selectedYear, setSelectedYear] = useState<string>('全部');
  const [yearSortOrder, setYearSortOrder] = useState<'none' | 'desc' | 'asc'>('none');
  const [focusedFilterKey, setFocusedFilterKey] = useState<string | null>(null);
  const [showFilterDialog, setShowFilterDialog] = useState(false);
  const [activeFilterKey, setActiveFilterKey] = useState<string | null>(null);
  const filterSelectorRef = useRef<any>(null);

  // 虚拟化相关
  const [focusedRowIndex, setFocusedRowIndex] = useState(0);
  const currentRowRef = useRef<number>(-1);
  const rowRefs = useRef<Map<number, any>>(new Map());
  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const { height: screenHeight } = useWindowDimensions();

  // 动画值
  const itemAnimations = useRef<Record<string, Animated.Value>>({}).current;

  // 搜索历史项的位置信息
  const historyItemPositions = useRef<Map<number, number>>(new Map());
  const [firstRowMaxY, setFirstRowMaxY] = useState<number>(0);

  // 获取搜索按钮和搜索输入框的节点句柄
  useEffect(() => {
    if (searchButtonRef.current) {
      const handle = findNodeHandle(searchButtonRef.current);
      setSearchButtonHandle(handle);
    }
    if (searchInputRef.current) {
      const handle = findNodeHandle(searchInputRef.current);
      setSearchInputHandle(handle);
    }
  }, [focusedItem]); // 当焦点改变时更新

  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  // 更新聚合结果
  const updateAggregatedResults = useCallback((results: SearchResult[]) => {
    const newAggregatedResults = new Map<string, AggregatedSearchResult>();
    const newOrderedKeys: string[] = [];

    for (const result of results) {
      const key = generateAggregationKey(result.title, result.year, result.episodes.length);

      if (newAggregatedResults.has(key)) {
        // 已存在，添加到现有聚合结果中
        const existing = newAggregatedResults.get(key)!;
        newAggregatedResults.set(key, addResultToAggregated(existing, result));
      } else {
        // 新的聚合结果
        newAggregatedResults.set(key, createAggregatedSearchResult(result));
        newOrderedKeys.push(key);
      }
    }

    setAggregatedResults(newAggregatedResults);
    setOrderedKeys(newOrderedKeys);
  }, []);

  useEffect(() => {
    loadSearchHistory();
    
    // 设置搜索事件监听器
    const unsubscribeResults = SearchService.addEventListener<SearchResult[]>('results', (results) => {
      if (results.length > 0) {
        setSearchResults((prev) => {
          const updated = [...prev, ...results];
          // 实时更新聚合结果
          updateAggregatedResults(updated);
          return updated;
        });
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
  }, [updateAggregatedResults]);

  const loadSearchHistory = async () => {
    setLoading(true);
    const result = await userDataCacheService.getSearchHistory();
    if (result.success && result.data) {
      setSearchHistory(result.data);
      // 重置位置信息
      historyItemPositions.current.clear();
      setFirstRowMaxY(0);
    }
    setLoading(false);
  };

  // 处理搜索历史项的布局测量
  const handleHistoryItemLayout = useCallback((index: number, event: any) => {
    const { y, height } = event.nativeEvent.layout;
    historyItemPositions.current.set(index, y);

    // 计算第一行的最大 Y 值
    // 第一个元素的 Y 值作为基准
    if (index === 0) {
      setFirstRowMaxY(y + height);
    } else if (historyItemPositions.current.has(0)) {
      const firstItemY = historyItemPositions.current.get(0)!;
      // 如果当前项的 Y 值与第一项相近（在同一行），更新最大 Y 值
      if (Math.abs(y - firstItemY) < 5) {
        setFirstRowMaxY(Math.max(firstRowMaxY, y + height));
      }
    }
  }, [firstRowMaxY]);

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
    setAggregatedResults(new Map());
    setOrderedKeys([]);
    setSearchProgress(null);
    setHasReceivedStart(true); // 立即设置为 true，显示搜索结果区域
    setSearchError(null);
    setIsSearching(true);
    setFocusedRowIndex(0);
    currentRowRef.current = -1;
    
    // 重置筛选状态
    setSelectedSource('全部');
    setSelectedTitle('全部');
    setSelectedYear('全部');
    setYearSortOrder('none');
    
    // 自动聚焦到搜索按钮
    requestAnimationFrame(() => {
      if (searchButtonRef.current) {
        searchButtonRef.current.setNativeProps?.({ hasTVPreferredFocus: true });
      }
    });
    
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
    
    // 自动发起搜索
    // 添加到搜索历史
    await userDataCacheService.addSearchHistory(query.trim());
    
    // 重新加载搜索历史
    await loadSearchHistory();
    
    // 重置搜索状态
    setSearchResults([]);
    setAggregatedResults(new Map());
    setOrderedKeys([]);
    setSearchProgress(null);
    setHasReceivedStart(true); // 立即设置为 true，显示搜索结果区域
    setSearchError(null);
    setIsSearching(true);
    setFocusedRowIndex(0);
    currentRowRef.current = -1;
    
    // 重置筛选状态
    setSelectedSource('全部');
    setSelectedTitle('全部');
    setSelectedYear('全部');
    setYearSortOrder('none');
    
    // 自动聚焦到搜索按钮
    requestAnimationFrame(() => {
      if (searchButtonRef.current) {
        searchButtonRef.current.setNativeProps?.({ hasTVPreferredFocus: true });
      }
    });
    
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

  // 获取所有可用的来源、标题和年份选项
  const filterOptions = useMemo(() => {
    const sources = new Set<string>();
    const titles = new Set<string>();
    const years = new Set<string>();

    orderedKeys.forEach(key => {
      const aggregated = aggregatedResults.get(key)!;
      aggregated.sourceNames.forEach((source: string) => sources.add(source));
      titles.add(aggregated.title);
      if (aggregated.year) {
        years.add(aggregated.year);
      }
    });

    // 年份排序：unknown在最后，其他按数字降序
    const sortedYears = Array.from(years).sort((a, b) => {
      if (a === 'unknown') return 1;
      if (b === 'unknown') return -1;
      return parseInt(b) - parseInt(a);
    });

    return {
      sources: ['全部', ...Array.from(sources).sort()].map(s => ({ label: s, value: s })),
      titles: ['全部', ...Array.from(titles).sort()].map(t => ({ label: t, value: t })),
      years: ['全部', ...sortedYears].map(y => ({ label: y, value: y })),
    };
  }, [orderedKeys, aggregatedResults]);

  // 将聚合结果转换为 VideoInfo 数组，并应用筛选和排序
  const aggregatedVideoInfos = useMemo(() => {
    let filtered = orderedKeys.map(key => {
      const aggregated = aggregatedResults.get(key)!;
      return aggregatedSearchResultToVideoInfo(aggregated);
    });

    // 应用来源筛选
    if (selectedSource !== '全部') {
      filtered = filtered.filter(video => {
        const aggregated = aggregatedResults.get(
          generateAggregationKey(video.title, video.year || '', video.totalEpisodes || 0)
        );
        return aggregated?.sourceNames.includes(selectedSource);
      });
    }

    // 应用标题筛选
    if (selectedTitle !== '全部') {
      filtered = filtered.filter(video => video.title === selectedTitle);
    }

    // 应用年份筛选
    if (selectedYear !== '全部') {
      filtered = filtered.filter(video => video.year === selectedYear);
    }

    // 应用年份排序
    if (yearSortOrder !== 'none') {
      filtered = [...filtered].sort((a, b) => {
        const yearA = a.year || 'unknown';
        const yearB = b.year || 'unknown';
        
        // unknown 始终排在最后
        if (yearA === 'unknown' && yearB !== 'unknown') return 1;
        if (yearA !== 'unknown' && yearB === 'unknown') return -1;
        if (yearA === 'unknown' && yearB === 'unknown') return 0;
        
        const numA = parseInt(yearA);
        const numB = parseInt(yearB);
        return yearSortOrder === 'desc' ? numB - numA : numA - numB;
      });
    }

    return filtered;
  }, [orderedKeys, aggregatedResults, selectedSource, selectedTitle, selectedYear, yearSortOrder]);

  // 将聚合结果分组为行
  const rows = useMemo(() => {
    const result = [];
    for (let i = 0; i < aggregatedVideoInfos.length; i += COLUMNS) {
      result.push(aggregatedVideoInfos.slice(i, i + COLUMNS));
    }
    return result;
  }, [aggregatedVideoInfos]);

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

  // 获取筛选器第一个元素的句柄
  const [filterFirstHandle, setFilterFirstHandle] = useState<number | null>(null);

  useEffect(() => {
    if (filterSelectorRef.current?.getFirstOptionRef) {
      const firstOptionRef = filterSelectorRef.current.getFirstOptionRef();
      if (firstOptionRef) {
        const handle = findNodeHandle(firstOptionRef);
        setFilterFirstHandle(handle);
      }
    }
  }, [focusedFilterKey]);

  // 计算底部 padding
  const bottomPadding = Math.max(0, (screenHeight / 2) - 67.5);

  // 处理年份排序切换
  const handleYearSortToggle = () => {
    setYearSortOrder(prev => {
      if (prev === 'none') return 'desc';
      if (prev === 'desc') return 'asc';
      return 'none';
    });
  };

  // 获取年份排序图标
  const getYearSortIcon = () => {
    if (yearSortOrder === 'desc') return ArrowDown;
    if (yearSortOrder === 'asc') return ArrowUp;
    return ArrowUpDown;
  };

  const YearSortIcon = getYearSortIcon();

  // 处理筛选器选择
  const handleFilterSelect = (key: string) => {
    setActiveFilterKey(key);
    setShowFilterDialog(true);
  };

  // 处理筛选对话框确认
  const handleFilterConfirm = (value: string) => {
    if (activeFilterKey === 'source') {
      setSelectedSource(value);
    } else if (activeFilterKey === 'title') {
      setSelectedTitle(value);
    } else if (activeFilterKey === 'year') {
      setSelectedYear(value);
    }
    setShowFilterDialog(false);
    setActiveFilterKey(null);
  };

  // 获取当前筛选对话框的选项
  const getFilterDialogOptions = () => {
    if (activeFilterKey === 'source') return filterOptions.sources;
    if (activeFilterKey === 'title') return filterOptions.titles;
    if (activeFilterKey === 'year') return filterOptions.years;
    return [];
  };

  // 获取当前筛选对话框的选中值
  const getFilterDialogValue = () => {
    if (activeFilterKey === 'source') return selectedSource;
    if (activeFilterKey === 'title') return selectedTitle;
    if (activeFilterKey === 'year') return selectedYear;
    return '全部';
  };

  // 获取当前筛选对话框的标题
  const getFilterDialogTitle = () => {
    if (activeFilterKey === 'source') return '选择来源';
    if (activeFilterKey === 'title') return '选择标题';
    if (activeFilterKey === 'year') return '选择年份';
    return '';
  };

  return (
    <View style={[styles.container, { paddingHorizontal: layout.horizontalPadding, paddingBottom: bottomPadding }]}>
      {/* 搜索栏 */}
      <TVFocusGuideView 
        style={styles.searchContainer} 
        trapFocusLeft 
        trapFocusRight
      >
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
          />
        </View>
        <Animated.View style={{ transform: [{ scale: getItemAnimation('search-button') }] }}>
          <Pressable
            ref={searchButtonRef}
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
            nextFocusDown={hasReceivedStart ? (filterFirstHandle ?? undefined) : undefined}
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

            {/* 筛选器 */}
            <View style={styles.filterContainer}>
              {/* 筛选选择器 */}
              <TransparentFilterSelector
                ref={filterSelectorRef}
                options={[
                  { key: 'source', label: '来源', selectedValue: selectedSource },
                  { key: 'title', label: '标题', selectedValue: selectedTitle },
                  { key: 'year', label: '年份', selectedValue: selectedYear },
                ]}
                focusedKey={focusedFilterKey}
                onFocus={setFocusedFilterKey}
                onBlur={() => setFocusedFilterKey(null)}
                onSelect={handleFilterSelect}
                nextFocusUp={searchInputHandle ?? undefined}
              />

              {/* 年份排序按钮 */}
              <Animated.View style={{ transform: [{ scale: getItemAnimation('year-sort') }] }}>
                <Pressable
                  style={[
                    styles.sortButton,
                    focusedFilterKey === 'year-sort' && styles.sortButtonFocused,
                  ]}
                  onPress={handleYearSortToggle}
                  onFocus={() => setFocusedFilterKey('year-sort')}
                  onBlur={() => setFocusedFilterKey(null)}
                  nextFocusUp={searchInputHandle ?? undefined}
                >
                  <Text style={[
                    styles.sortButtonText,
                    focusedFilterKey === 'year-sort' && styles.sortButtonTextFocused,
                  ]}>
                    年份
                  </Text>
                  <YearSortIcon 
                    size={13} 
                    color={focusedFilterKey === 'year-sort' ? '#0a0a0a' : '#ffffff'}
                    style={styles.sortIcon}
                  />
                </Pressable>
              </Animated.View>
            </View>

            {/* 搜索结果网格 */}
            {searchError && (
              <Text style={styles.errorText}>{searchError}</Text>
            )}
            
            {aggregatedVideoInfos.length === 0 && !isSearching ? (
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
                      {row.map((videoInfo, colIndex) => (
                        <View 
                          key={videoInfo.id}
                          style={[
                            styles.cardWrapper,
                            colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                          ]}
                        >
                          <VideoCard
                            videoInfo={videoInfo}
                            from='search'
                            onPress={() => console.log('Pressed:', videoInfo.title)}
                            onFocus={() => handleCardFocus(rowIndex)}
                            nextFocusUp={rowIndex === 0 ? (filterFirstHandle ?? undefined) : undefined}
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
                  
                  // 通过实际测量判断是否在第一行
                  const itemY = historyItemPositions.current.get(index);
                  const firstItemY = historyItemPositions.current.get(0);
                  const isFirstRow = itemY !== undefined && firstItemY !== undefined && Math.abs(itemY - firstItemY) < 5;

                  return (
                    <Animated.View 
                      key={`${item}-${index}`} 
                      style={{ transform: [{ scale: animation }] }}
                      onLayout={(e) => handleHistoryItemLayout(index, e)}
                    >
                      <Pressable
                        style={[
                          styles.historyItem,
                          focusedItem === itemKey && styles.historyItemFocused,
                        ]}
                        onPress={() => handleHistoryItemPress(item)}
                        onFocus={() => setFocusedItem(itemKey)}
                        onBlur={() => setFocusedItem(null)}
                        nextFocusUp={isFirstRow ? (searchInputHandle ?? undefined) : undefined}
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

      {/* 筛选对话框 */}
      {showFilterDialog && (
        <FilterDialog
          visible={showFilterDialog}
          title={getFilterDialogTitle()}
          options={getFilterDialogOptions()}
          selectedValue={getFilterDialogValue()}
          onSelect={handleFilterConfirm}
          onClose={() => {
            setShowFilterDialog(false);
            setActiveFilterKey(null);
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 20,
  },
  searchContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 18,
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
    marginBottom: 8,
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
    paddingHorizontal: 14.4,
    paddingVertical: 5.4,
    borderRadius: 12.6,
    alignSelf: 'flex-start',
  },
  historyItemFocused: {
    backgroundColor: '#ffffff',
  },
  historyItemText: {
    fontSize: 10.8,
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
  filterContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3.6,
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  sortButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    height: 25,
    paddingHorizontal: 10.8,
    borderRadius: 13,
  },
  sortButtonFocused: {
    backgroundColor: '#ffffff',
  },
  sortButtonText: {
    fontSize: 10,
    fontWeight: '600',
    color: '#ffffff',
  },
  sortButtonTextFocused: {
    color: '#0a0a0a',
  },
  sortIcon: {
    marginLeft: 3,
  },
});
