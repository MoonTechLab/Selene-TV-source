import { StyleSheet, View, Text, Dimensions, useWindowDimensions, findNodeHandle } from 'react-native';
import { useState, useMemo, useRef, useEffect, useCallback, forwardRef } from 'react';
import FilterSelector from '../FilterSelector';
import TransparentFilterSelector from '../TransparentFilterSelector';
import FilterDialog, { SelectorOption } from '../FilterDialog';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import doubanService from '@/services/doubanService';

const CATEGORY_OPTIONS = [
  { key: 'all', label: '全部' },
  { key: 'popular', label: '热门电影' },
  { key: 'latest', label: '最新电影' },
  { key: 'douban', label: '豆瓣高分' },
  { key: 'hidden', label: '冷门佳片' },
];

const REGION_OPTIONS = [
  { key: 'all', label: '全部' },
  { key: 'chinese', label: '华语' },
  { key: 'western', label: '欧美' },
  { key: 'korean', label: '韩国' },
  { key: 'japanese', label: '日本' },
];

// 全部分类的筛选选项
const FILTER_OPTIONS = [
  { key: 'type', label: '类型' },
  { key: 'region', label: '地区' },
  { key: 'year', label: '年代' },
  { key: 'sort', label: '排序' },
];

const MOVIE_TYPE_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '喜剧', value: 'comedy' },
  { label: '爱情', value: 'romance' },
  { label: '动作', value: 'action' },
  { label: '科幻', value: 'sci-fi' },
  { label: '悬疑', value: 'suspense' },
  { label: '犯罪', value: 'crime' },
  { label: '惊悚', value: 'thriller' },
  { label: '冒险', value: 'adventure' },
  { label: '音乐', value: 'music' },
  { label: '历史', value: 'history' },
  { label: '奇幻', value: 'fantasy' },
  { label: '恐怖', value: 'horror' },
  { label: '战争', value: 'war' },
  { label: '传记', value: 'biography' },
  { label: '歌舞', value: 'musical' },
  { label: '武侠', value: 'wuxia' },
  { label: '情色', value: 'erotic' },
  { label: '灾难', value: 'disaster' },
  { label: '西部', value: 'western' },
  { label: '纪录片', value: 'documentary' },
  { label: '短片', value: 'short' },
];

const MOVIE_REGION_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '华语', value: 'chinese' },
  { label: '欧美', value: 'western' },
  { label: '韩国', value: 'korean' },
  { label: '日本', value: 'japanese' },
  { label: '中国大陆', value: 'mainland_china' },
  { label: '美国', value: 'usa' },
  { label: '中国香港', value: 'hong_kong' },
  { label: '中国台湾', value: 'taiwan' },
  { label: '英国', value: 'uk' },
  { label: '法国', value: 'france' },
  { label: '德国', value: 'germany' },
  { label: '意大利', value: 'italy' },
  { label: '西班牙', value: 'spain' },
  { label: '印度', value: 'india' },
  { label: '泰国', value: 'thailand' },
  { label: '俄罗斯', value: 'russia' },
  { label: '加拿大', value: 'canada' },
  { label: '澳大利亚', value: 'australia' },
  { label: '爱尔兰', value: 'ireland' },
  { label: '瑞典', value: 'sweden' },
  { label: '巴西', value: 'brazil' },
  { label: '丹麦', value: 'denmark' },
];

const MOVIE_YEAR_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '2020年代', value: '2020s' },
  { label: '2025', value: '2025' },
  { label: '2024', value: '2024' },
  { label: '2023', value: '2023' },
  { label: '2022', value: '2022' },
  { label: '2021', value: '2021' },
  { label: '2020', value: '2020' },
  { label: '2019', value: '2019' },
  { label: '2010年代', value: '2010s' },
  { label: '2000年代', value: '2000s' },
  { label: '90年代', value: '1990s' },
  { label: '80年代', value: '1980s' },
  { label: '70年代', value: '1970s' },
  { label: '60年代', value: '1960s' },
  { label: '更早', value: 'earlier' },
];

const MOVIE_SORT_OPTIONS: SelectorOption[] = [
  { label: '综合排序', value: 'T' },
  { label: '近期热度', value: 'U' },
  { label: '首映时间', value: 'R' },
  { label: '高分优先', value: 'S' },
];

// 分类和地区的 value map（value 就是其文本）
const CATEGORY_VALUE_MAP: Record<string, string> = {
  popular: '热门',
  latest: '最新',
  douban: '豆瓣高分',
  hidden: '冷门佳片',
};

const REGION_VALUE_MAP: Record<string, string> = {
  all: '全部',
  chinese: '华语',
  western: '欧美',
  korean: '韩国',
  japanese: '日本',
};

const COLUMNS = 7;
const CARD_WIDTH = 100; // VideoCard 的宽度
const CARD_MARGIN_RIGHT = 20; // VideoCard 自带的 marginRight
const RENDER_BUFFER = 3; // 当前行上下各保留 3 行，共 7 行
const ROW_GAP = 20; // 行间距
const ROW_HEIGHT = 150 + ROW_GAP; // 行高：150(卡片) + 行间距

// 计算布局参数
const calculateLayout = () => {
  const screenWidth = Dimensions.get('window').width;
  
  // 7 个卡片宽度
  const totalCardWidth = CARD_WIDTH * COLUMNS; // 700
  
  // 剩余空间
  const availableSpace = screenWidth - totalCardWidth;
  
  // 左右 padding 各占剩余空间的 15%（共 30%），剩余 70% 分配给 6 个间距
  const horizontalPadding = Math.floor(availableSpace * 0.15);
  const totalGapSpace = availableSpace * 0.7; // 70% 给间距
  const cardGap = Math.floor(totalGapSpace / (COLUMNS - 1)); // 6 个间距
  
  return {
    horizontalPadding: Math.max(20, horizontalPadding),
    cardGap: Math.max(20, cardGap), // 最小 20
  };
};

interface MovieTabProps {
  firstSelectorRef?: React.RefObject<any>;
}

const MovieTab = forwardRef<any, MovieTabProps>(({ firstSelectorRef }, ref) => {
  const [selectedCategory, setSelectedCategory] = useState('popular');
  const [selectedRegion, setSelectedRegion] = useState('all');
  const [focusedCategoryKey, setFocusedCategoryKey] = useState<string | null>(null);
  const [focusedRegionKey, setFocusedRegionKey] = useState<string | null>(null);
  
  // 全部分类的筛选状态
  const [focusedFilterKey, setFocusedFilterKey] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState('all');
  const [selectedAllRegion, setSelectedAllRegion] = useState('all');
  const [selectedYear, setSelectedYear] = useState('all');
  const [selectedSort, setSelectedSort] = useState('T');
  
  // 筛选对话框状态
  const [filterDialogVisible, setFilterDialogVisible] = useState(false);
  const [currentFilterType, setCurrentFilterType] = useState<string | null>(null);
  const filterDialogRef = useRef<any>(null);
  const transparentFilterRef = useRef<any>(null);
  
  // 数据状态
  const [movies, setMovies] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const pageLimit = 25;

  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const { height: screenHeight } = useWindowDimensions();
  const currentRowRef = useRef<number>(-1);
  const [focusedRowIndex, setFocusedRowIndex] = useState(0);
  const rowRefs = useRef<Map<number, any>>(new Map());
  const loadingTriggeredForPageRef = useRef<number>(1); // 记录已触发加载的页码
  const regionSelectorRef = useRef<any>(null);
  const [regionSelectorHandle, setRegionSelectorHandle] = useState<number | null>(null);

  // 获取地区筛选器的节点句柄
  useEffect(() => {
    if (regionSelectorRef.current) {
      const handle = findNodeHandle(regionSelectorRef.current);
      setRegionSelectorHandle(handle);
    }
  }, [selectedRegion]); // 当选中项改变时更新

  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  // 获取当前筛选状态
  const getCurrentFilterState = useCallback(() => {
    if (selectedCategory === 'all') {
      return `all-${selectedType}-${selectedAllRegion}-${selectedYear}-${selectedSort}`;
    }
    return `${selectedCategory}-${selectedRegion}`;
  }, [selectedCategory, selectedRegion, selectedType, selectedAllRegion, selectedYear, selectedSort]);

  // 加载数据
  const loadData = useCallback(async (isRefresh: boolean = false) => {
    if (isLoading) {
      return;
    }
    if (!isRefresh && !hasMore) {
      return;
    }

    const currentPage = isRefresh ? 0 : page;
    const requestFilterState = getCurrentFilterState();

    setIsLoading(true);
    if (isRefresh) {
      setErrorMessage(null);
    }

    try {
      let allMovies: any[] = [];
      
      if (selectedCategory === 'all') {
        // 全部分类：使用 fetchDoubanRecommends API
        const typeOption = MOVIE_TYPE_OPTIONS.find(opt => opt.value === selectedType);
        const regionOption = MOVIE_REGION_OPTIONS.find(opt => opt.value === selectedAllRegion);
        const yearOption = MOVIE_YEAR_OPTIONS.find(opt => opt.value === selectedYear);
        
        const baseParams = {
          kind: 'movie' as const,
          category: selectedType === 'all' ? 'all' : typeOption?.label,
          region: selectedAllRegion === 'all' ? 'all' : regionOption?.label,
          year: selectedYear === 'all' ? 'all' : yearOption?.label,
          sort: selectedSort,
          pageLimit,
        };
        
        // 首次加载时加载两页数据
        if (isRefresh) {
          const [result1, result2] = await Promise.all([
            doubanService.fetchDoubanRecommends({ ...baseParams, page: 0 }),
            doubanService.fetchDoubanRecommends({ ...baseParams, page: 1 }),
          ]);
          
          // 检查当前筛选状态是否仍然与发起请求时一致
          const currentFilterState = getCurrentFilterState();
          if (requestFilterState !== currentFilterState) {
            return;
          }
          
          if (result1.success && result1.data) {
            allMovies = [...result1.data];
          }
          if (result2.success && result2.data) {
            allMovies = [...allMovies, ...result2.data];
          }
          
          if (allMovies.length > 0) {
            setMovies(allMovies);
            setPage(2); // 已加载两页，下次从第3页开始
            setHasMore(true);
          } else {
            setMovies([]);
            setHasMore(false);
            if (!result1.success) {
              setErrorMessage(result1.error || '加载失败');
            }
          }
        } else {
          // 非首次加载，正常加载单页
          const result = await doubanService.fetchDoubanRecommends({
            ...baseParams,
            page: currentPage,
          });
          
          // 检查当前筛选状态是否仍然与发起请求时一致
          const currentFilterState = getCurrentFilterState();
          if (requestFilterState !== currentFilterState) {
            return;
          }
          
          if (result.success && result.data) {
            setMovies(prev => [...prev, ...result.data!]);
            setPage(currentPage + 1);
            
            if (result.data.length === 0) {
              setHasMore(false);
            }
          } else {
            setErrorMessage(result.error || '加载失败');
          }
        }
      } else {
        // 其他分类：使用原有逻辑
        const result = await doubanService.getCategoryData({
          kind: 'movie',
          category: CATEGORY_VALUE_MAP[selectedCategory],
          type: REGION_VALUE_MAP[selectedRegion],
          page: currentPage,
          pageLimit,
        });
        
        // 检查当前筛选状态是否仍然与发起请求时一致
        const currentFilterState = getCurrentFilterState();
        if (requestFilterState !== currentFilterState) {
          return;
        }

        if (result.success && result.data) {
          setMovies(prev => {
            const newMovies = isRefresh ? result.data! : [...prev, ...result.data!];
            return newMovies;
          });
          setPage(currentPage + 1);
          
          // 只有当返回的数据为空时才停止分页
          if (result.data.length === 0) {
            setHasMore(false);
          }
        } else {
          setErrorMessage(result.error || '加载失败');
        }
      }
    } catch (error) {
      setErrorMessage('加载失败');
    } finally {
      setIsLoading(false);
    }
  }, [isLoading, hasMore, page, selectedCategory, selectedRegion, selectedType, selectedAllRegion, selectedYear, selectedSort, getCurrentFilterState, pageLimit]);



  // 首次加载
  useEffect(() => {
    loadData(true);
  }, []);

  // 当切换到"全部"分类时，重置所有筛选项
  useEffect(() => {
    if (selectedCategory === 'all') {
      setSelectedType('all');
      setSelectedAllRegion('all');
      setSelectedYear('all');
      setSelectedSort('T');
    }
  }, [selectedCategory]);

  // 当筛选条件改变时，重新加载数据
  useEffect(() => {
    setMovies([]);
    setPage(1);
    setHasMore(true);
    loadingTriggeredForPageRef.current = 0; // 重置加载触发标记
    loadData(true);
  }, [selectedCategory, selectedRegion, selectedType, selectedAllRegion, selectedYear, selectedSort]);

  // 将数据分组为行
  const rows = useMemo(() => {
    const result = [];
    for (let i = 0; i < movies.length; i += COLUMNS) {
      result.push(movies.slice(i, i + COLUMNS));
    }
    return result;
  }, [movies]);

  // 计算需要渲染的行范围（虚拟化）
  const visibleRows = useMemo(() => {
    const startRow = Math.max(0, focusedRowIndex - RENDER_BUFFER);
    const endRow = Math.min(rows.length, focusedRowIndex + RENDER_BUFFER + 1);
    
    // 确保最后一行总是被渲染，以便触发加载逻辑
    const shouldRenderLastRow = focusedRowIndex >= rows.length - RENDER_BUFFER - 1;
    
    return { 
      startRow, 
      endRow: shouldRenderLastRow ? rows.length : endRow 
    };
  }, [focusedRowIndex, rows.length]);

  const handleFilterFocus = (key: string, setter: (key: string) => void) => {
    setter(key);
    // 滚动到顶部
    if (mainScrollViewRef?.current) {
      mainScrollViewRef.current.scrollTo({ y: 0, animated: true });
    }
    // 重置当前行，以便下次聚焦卡片时能正确滚动
    currentRowRef.current = -1;
  };

  const handleFilterSelect = (key: string, setter: (key: string) => void) => {
    setter(key);
  };

  // 处理全部分类的筛选项聚焦
  const handleAllFilterFocus = (key: string) => {
    setFocusedFilterKey(key);
    // 滚动到顶部
    if (mainScrollViewRef?.current) {
      mainScrollViewRef.current.scrollTo({ y: 0, animated: true });
    }
    // 重置当前行
    currentRowRef.current = -1;
  };

  // 处理全部分类的筛选项选择（打开对话框）
  const handleAllFilterSelect = (key: string) => {
    setCurrentFilterType(key);
    setFilterDialogVisible(true);
  };

  // 获取当前筛选对话框的选项和选中值
  const getCurrentFilterOptions = () => {
    switch (currentFilterType) {
      case 'type':
        return { options: MOVIE_TYPE_OPTIONS, selectedValue: selectedType, title: '类型' };
      case 'region':
        return { options: MOVIE_REGION_OPTIONS, selectedValue: selectedAllRegion, title: '地区' };
      case 'year':
        return { options: MOVIE_YEAR_OPTIONS, selectedValue: selectedYear, title: '年代' };
      case 'sort':
        return { options: MOVIE_SORT_OPTIONS, selectedValue: selectedSort, title: '排序' };
      default:
        return { options: [], selectedValue: '', title: '' };
    }
  };

  // 处理筛选对话框的选择
  const handleFilterDialogSelect = (value: string) => {
    switch (currentFilterType) {
      case 'type':
        setSelectedType(value);
        break;
      case 'region':
        setSelectedAllRegion(value);
        break;
      case 'year':
        setSelectedYear(value);
        break;
      case 'sort':
        setSelectedSort(value);
        break;
    }
  };

  // 获取筛选项显示的值
  const getFilterDisplayValue = (key: string) => {
    let value = '';
    let options: SelectorOption[] = [];
    
    switch (key) {
      case 'type':
        value = selectedType;
        options = MOVIE_TYPE_OPTIONS;
        break;
      case 'region':
        value = selectedAllRegion;
        options = MOVIE_REGION_OPTIONS;
        break;
      case 'year':
        value = selectedYear;
        options = MOVIE_YEAR_OPTIONS;
        break;
      case 'sort':
        value = selectedSort;
        options = MOVIE_SORT_OPTIONS;
        break;
    }
    
    // 查找对应的 label
    const option = options.find(opt => opt.value === value);
    return option?.label || '';
  };

  const handleCardFocus = (rowIndex: number) => {
    // 先更新聚焦行索引（用于虚拟化），确保目标行已渲染
    setFocusedRowIndex(rowIndex);

    // 判断是否需要加载下一页
    const shouldLoadNextPage = rowIndex === rows.length - 1 && hasMore && !isLoading && loadingTriggeredForPageRef.current < page;

    // 当切换到新行时，滚动到居中位置
    if (currentRowRef.current !== rowIndex && mainScrollViewRef?.current) {
      currentRowRef.current = rowIndex;
      
      // 使用行容器的 ref 进行测量
      const rowRef = rowRefs.current.get(rowIndex);
      if (rowRef) {
        // 延迟测量，确保虚拟化渲染完成
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            rowRef.measureLayout(
              mainScrollViewRef.current as any,
              (_x: number, y: number, _width: number, height: number) => {
                const scrollToY = Math.max(0, y - (screenHeight / 2) + (height / 2));
                // 使用非动画滚动，避免与系统滚动冲突
                mainScrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
                
                // 滚动完成后，如果是最后一行，触发加载下一页
                if (shouldLoadNextPage) {
                  // 使用 setTimeout 确保滚动动画有时间执行
                  setTimeout(() => {
                    loadingTriggeredForPageRef.current = page;
                    loadData(false);
                  }, 300); // 给滚动动画留出时间
                }
              },
              () => {
                // 测量失败时的回调，仍然尝试加载
                if (shouldLoadNextPage) {
                  loadingTriggeredForPageRef.current = page;
                  loadData(false);
                }
              }
            );
          });
        });
      }
    } else if (shouldLoadNextPage) {
      // 如果不需要滚动（已经在当前行），直接加载
      loadingTriggeredForPageRef.current = page;
      loadData(false);
    }
  };

  // 计算底部 padding：让最后一行能居中，需要预留 (屏幕高度/2 - 卡片高度/2)
  const bottomPadding = Math.max(0, (screenHeight / 2) - 75); // 75 是卡片高度的一半

  const filterConfig = getCurrentFilterOptions();

  return (
    <View style={[styles.container, { paddingHorizontal: layout.horizontalPadding, paddingBottom: bottomPadding }]}>
      {/* 筛选区域 */}
      <View style={styles.filterSection}>
        {/* 分类筛选行 */}
        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>分类</Text>
          <FilterSelector
            ref={firstSelectorRef}
            options={CATEGORY_OPTIONS}
            selectedKey={selectedCategory}
            focusedKey={focusedCategoryKey}
            onFocus={(key) => handleFilterFocus(key, setFocusedCategoryKey)}
            onBlur={() => setFocusedCategoryKey(null)}
            onSelect={(key) => handleFilterSelect(key, setSelectedCategory)}
            nextFocusDownMap={{
              all: transparentFilterRef?.current,
            }}
          />
        </View>

        {/* 根据选中的分类显示不同的筛选行 */}
        {selectedCategory === 'all' ? (
          // 全部分类：显示透明筛选器
          <View style={styles.filterRow}>
            <Text style={styles.filterLabel}>筛选</Text>
            <TransparentFilterSelector
              ref={transparentFilterRef}
              options={FILTER_OPTIONS.map(opt => ({
                ...opt,
                selectedValue: getFilterDisplayValue(opt.key)
              }))}
              focusedKey={focusedFilterKey}
              onFocus={handleAllFilterFocus}
              onBlur={() => setFocusedFilterKey(null)}
              onSelect={handleAllFilterSelect}
              nextFocusUp={firstSelectorRef?.current}
            />
          </View>
        ) : (
          // 其他分类：显示地区筛选
          <View style={styles.filterRow}>
            <Text style={styles.filterLabel}>地区</Text>
            <FilterSelector
              ref={regionSelectorRef}
              options={REGION_OPTIONS}
              selectedKey={selectedRegion}
              focusedKey={focusedRegionKey}
              onFocus={(key) => handleFilterFocus(key, setFocusedRegionKey)}
              onBlur={() => setFocusedRegionKey(null)}
              onSelect={(key) => handleFilterSelect(key, setSelectedRegion)}
              nextFocusUp={firstSelectorRef?.current}
            />
          </View>
        )}
      </View>

      {/* 筛选对话框 */}
      <FilterDialog
        ref={filterDialogRef}
        visible={filterDialogVisible}
        title={filterConfig.title}
        options={filterConfig.options}
        selectedValue={filterConfig.selectedValue}
        onSelect={handleFilterDialogSelect}
        onClose={() => setFilterDialogVisible(false)}
      />

      {/* 内容区域 - 网格布局 */}
      <View style={styles.contentSection}>
        {errorMessage ? (
          <Text style={styles.errorText}>{errorMessage}</Text>
        ) : (
          <>
            {rows.map((row, rowIndex) => {
              // 虚拟化：只渲染可见范围内的行
              const isVisible = rowIndex >= visibleRows.startRow && rowIndex < visibleRows.endRow;
              
              if (!isVisible) {
                // 占位元素，保持布局高度
                return <View key={`row-${rowIndex}`} style={styles.rowPlaceholder} />;
              }

              // 判断是否是最后一行且正在加载
              const isLastRow = rowIndex === rows.length - 1;
              const shouldShowSkeletons = isLastRow && isLoading && row.length < COLUMNS;
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
                  {row.map((movie, colIndex) => (
                    <View 
                      key={movie.id}
                      style={[
                        styles.cardWrapper,
                        colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                      ]}
                    >
                      <VideoCard
                        title={movie.title}
                        coverUrl={movie.poster}
                        onPress={() => console.log('Pressed:', movie.title)}
                        onFocus={() => handleCardFocus(rowIndex)}
                        nextFocusUp={rowIndex === 0 ? (regionSelectorHandle ?? undefined) : undefined}
                      />
                    </View>
                  ))}
                  {/* 填充最后一行的骨架卡片 */}
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
            
            {/* 加载中骨架屏 - 只在最后一行是满行时显示额外的骨架行 */}
            {isLoading && (rows.length === 0 || rows[rows.length - 1].length === COLUMNS) && (
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
          </>
        )}
      </View>
    </View>
  );
});

MovieTab.displayName = 'MovieTab';

export default MovieTab;

const styles = StyleSheet.create({
  container: {
    // paddingBottom 通过动态计算设置为屏幕高度的一半
  },
  filterSection: {
    paddingTop: 12,
    paddingBottom: 20,
    gap: 10,
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 0,
  },
  filterLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: '#ffffff',
    width: 40,
  },
  contentSection: {
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
  errorText: {
    fontSize: 14,
    color: '#ff6b6b',
    textAlign: 'center',
    marginTop: 20,
  },
});
