import { StyleSheet, View, Text, Dimensions, useWindowDimensions } from 'react-native';
import { useState, useMemo, useRef, useEffect, useCallback, forwardRef } from 'react';
import FilterSelector from '../FilterSelector';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import doubanService from '@/services/doubanService';

const CATEGORY_OPTIONS = [
  { key: 'recent', label: '最近热门' },
];

const REGION_OPTIONS = [
  { key: 'all', label: '全部' },
  { key: 'domestic', label: '国内' },
  { key: 'foreign', label: '国外' },
];

// 分类和地区的 value map
const CATEGORY_VALUE_MAP: Record<string, string> = {
  recent: 'show',
};

const REGION_VALUE_MAP: Record<string, string> = {
  all: 'show',
  domestic: 'show_domestic',
  foreign: 'show_foreign',
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

interface VarietyTabProps {
  firstSelectorRef?: React.RefObject<any>;
}

const VarietyTab = forwardRef<any, VarietyTabProps>(({ firstSelectorRef }, ref) => {
  const [selectedCategory, setSelectedCategory] = useState('recent');
  const [selectedRegion, setSelectedRegion] = useState('all');
  const [focusedCategoryKey, setFocusedCategoryKey] = useState<string | null>(null);
  const [focusedRegionKey, setFocusedRegionKey] = useState<string | null>(null);
  
  // 数据状态
  const [shows, setShows] = useState<any[]>([]);
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

  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  // 获取当前筛选状态
  const getCurrentFilterState = useCallback(() => {
    return `${selectedCategory}-${selectedRegion}`;
  }, [selectedCategory, selectedRegion]);

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
      const result = await doubanService.getCategoryData({
        kind: 'tv',
        category: 'show',
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
        setShows(prev => {
          const newShows = isRefresh ? result.data! : [...prev, ...result.data!];
          return newShows;
        });
        setPage(currentPage + 1);
        
        // 只有当返回的数据为空时才停止分页
        if (result.data.length === 0) {
          setHasMore(false);
        }
      } else {
        setErrorMessage(result.error || '加载失败');
      }
    } catch (error) {
      setErrorMessage('加载失败');
    } finally {
      setIsLoading(false);
    }
  }, [isLoading, hasMore, page, selectedRegion, getCurrentFilterState, pageLimit]);



  // 首次加载
  useEffect(() => {
    loadData(true);
  }, []);

  // 当筛选条件改变时，重新加载数据
  useEffect(() => {
    setShows([]);
    setPage(1);
    setHasMore(true);
    loadingTriggeredForPageRef.current = 0; // 重置加载触发标记
    loadData(true);
  }, [selectedCategory, selectedRegion]);

  // 将数据分组为行
  const rows = useMemo(() => {
    const result = [];
    for (let i = 0; i < shows.length; i += COLUMNS) {
      result.push(shows.slice(i, i + COLUMNS));
    }
    return result;
  }, [shows]);

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
          />
        </View>

        {/* 地区筛选行 */}
        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>地区</Text>
          <FilterSelector
            options={REGION_OPTIONS}
            selectedKey={selectedRegion}
            focusedKey={focusedRegionKey}
            onFocus={(key) => handleFilterFocus(key, setFocusedRegionKey)}
            onBlur={() => setFocusedRegionKey(null)}
            onSelect={(key) => handleFilterSelect(key, setSelectedRegion)}
          />
        </View>
      </View>

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
                  {row.map((show, colIndex) => (
                    <View 
                      key={show.id}
                      style={[
                        styles.cardWrapper,
                        colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                      ]}
                    >
                      <VideoCard
                        title={show.title}
                        coverUrl={show.poster}
                        onPress={() => console.log('Pressed:', show.title)}
                        onFocus={() => handleCardFocus(rowIndex)}
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

VarietyTab.displayName = 'VarietyTab';

export default VarietyTab;

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
