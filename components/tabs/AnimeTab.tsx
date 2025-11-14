import { StyleSheet, View, Text, Dimensions, useWindowDimensions, findNodeHandle } from 'react-native';
import { useState, useMemo, useRef, useEffect, useCallback, forwardRef } from 'react';
import FilterSelector from '../FilterSelector';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import bangumiService from '@/services/bangumiService';

const CATEGORY_OPTIONS = [
  { key: 'new', label: '新番放送' },
];

// 星期选项：周一到周日
const WEEKDAY_OPTIONS = [
  { key: '1', label: '周一' },
  { key: '2', label: '周二' },
  { key: '3', label: '周三' },
  { key: '4', label: '周四' },
  { key: '5', label: '周五' },
  { key: '6', label: '周六' },
  { key: '7', label: '周日' },
];

// 获取当前星期几（1-7，周一到周日）
const getCurrentWeekday = (): string => {
  const now = new Date();
  const day = now.getDay(); // 0 (Sunday) - 6 (Saturday)
  return day === 0 ? '7' : String(day);
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

interface AnimeTabProps {
  firstSelectorRef?: React.RefObject<any>;
}

const AnimeTab = forwardRef<any, AnimeTabProps>(({ firstSelectorRef }, ref) => {
  const [selectedCategory, setSelectedCategory] = useState('new');
  const [selectedWeekday, setSelectedWeekday] = useState(getCurrentWeekday());
  const [focusedCategoryKey, setFocusedCategoryKey] = useState<string | null>(null);
  const [focusedWeekdayKey, setFocusedWeekdayKey] = useState<string | null>(null);
  
  const weekdaySelectorRef = useRef<any>(null);
  const [weekdaySelectorHandle, setWeekdaySelectorHandle] = useState<number | null>(null);

  // 获取星期筛选器的节点句柄
  useEffect(() => {
    if (weekdaySelectorRef.current) {
      const handle = findNodeHandle(weekdaySelectorRef.current);
      setWeekdaySelectorHandle(handle);
    }
  }, [selectedWeekday]); // 当选中项改变时更新
  
  // 数据状态
  const [animes, setAnimes] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const { scrollViewRef: mainScrollViewRef } = useScrollContext();
  const { height: screenHeight } = useWindowDimensions();
  const currentRowRef = useRef<number>(-1);
  const [focusedRowIndex, setFocusedRowIndex] = useState(0);
  const rowRefs = useRef<Map<number, any>>(new Map());

  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  // 加载数据
  const loadData = useCallback(async () => {
    if (isLoading) {
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);

    try {
      const result = await bangumiService.getCalendarByWeekday(parseInt(selectedWeekday));

      if (result.success && result.data) {
        setAnimes(result.data);
      } else {
        setErrorMessage(result.error || '加载失败');
      }
    } catch (error) {
      setErrorMessage('加载失败');
    } finally {
      setIsLoading(false);
    }
  }, [isLoading, selectedWeekday]);

  // 首次加载
  useEffect(() => {
    loadData();
  }, []);

  // 当筛选条件改变时，重新加载数据
  useEffect(() => {
    setAnimes([]);
    loadData();
  }, [selectedCategory, selectedWeekday]);

  // 将数据分组为行
  const rows = useMemo(() => {
    const result = [];
    for (let i = 0; i < animes.length; i += COLUMNS) {
      result.push(animes.slice(i, i + COLUMNS));
    }
    return result;
  }, [animes]);

  // 计算需要渲染的行范围（虚拟化）
  const visibleRows = useMemo(() => {
    const startRow = Math.max(0, focusedRowIndex - RENDER_BUFFER);
    const endRow = Math.min(rows.length, focusedRowIndex + RENDER_BUFFER + 1);
    
    return { 
      startRow, 
      endRow
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
              },
              () => {
                // 测量失败时的回调
              }
            );
          });
        });
      }
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

        {/* 星期筛选行 */}
        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>星期</Text>
          <FilterSelector
            ref={weekdaySelectorRef}
            options={WEEKDAY_OPTIONS}
            selectedKey={selectedWeekday}
            focusedKey={focusedWeekdayKey}
            onFocus={(key) => handleFilterFocus(key, setFocusedWeekdayKey)}
            onBlur={() => setFocusedWeekdayKey(null)}
            onSelect={(key) => handleFilterSelect(key, setSelectedWeekday)}
            nextFocusUp={firstSelectorRef?.current}
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
                  {row.map((anime, colIndex) => (
                    <View 
                      key={anime.id}
                      style={[
                        styles.cardWrapper,
                        colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                      ]}
                    >
                      <VideoCard
                        title={anime.nameCn || anime.name}
                        coverUrl={anime.images?.large || anime.images?.common || ''}
                        onPress={() => console.log('Pressed:', anime.name)}
                        onFocus={() => handleCardFocus(rowIndex)}
                        nextFocusUp={rowIndex === 0 ? (weekdaySelectorHandle ?? undefined) : undefined}
                      />
                    </View>
                  ))}
                </View>
              );
            })}
            
            {/* 加载中骨架屏 */}
            {isLoading && animes.length === 0 && (
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

AnimeTab.displayName = 'AnimeTab';

export default AnimeTab;

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
