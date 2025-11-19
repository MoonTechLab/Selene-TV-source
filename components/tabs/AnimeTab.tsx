import { StyleSheet, View, Text, Dimensions, useWindowDimensions, findNodeHandle } from 'react-native';
import { useState, useMemo, useRef, useEffect, useCallback, forwardRef } from 'react';
import FilterSelector from '../FilterSelector';
import TransparentFilterSelector from '../TransparentFilterSelector';
import FilterDialog, { SelectorOption } from '../FilterDialog';
import VideoCard from '../VideoCard';
import VideoCardSkeleton from '../VideoCardSkeleton';
import { useScrollContext } from '@/contexts/ScrollContext';
import bangumiService from '@/services/bangumiService';
import doubanService from '@/services/doubanService';
import { doubanMovieToVideoInfo } from '@/types/douban';
import { bangumiItemToVideoInfo } from '@/types/bangumi';

const CATEGORY_OPTIONS = [
  { key: 'new', label: '每日放送' },
  { key: 'series', label: '番剧' },
  { key: 'movie', label: '剧场版' },
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

// 番剧和剧场版的筛选选项
const SERIES_FILTER_OPTIONS = [
  { key: 'type', label: '类型' },
  { key: 'region', label: '地区' },
  { key: 'year', label: '年代' },
  { key: 'platform', label: '平台' },
  { key: 'sort', label: '排序' },
];

const MOVIE_FILTER_OPTIONS = [
  { key: 'type', label: '类型' },
  { key: 'region', label: '地区' },
  { key: 'year', label: '年代' },
  { key: 'sort', label: '排序' },
];

// 番剧类型选项
const ANIME_TYPE_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '黑色幽默', value: 'dark_humor' },
  { label: '历史', value: 'history' },
  { label: '歌舞', value: 'musical' },
  { label: '励志', value: 'inspirational' },
  { label: '恶搞', value: 'parody' },
  { label: '治愈', value: 'healing' },
  { label: '运动', value: 'sports' },
  { label: '后宫', value: 'harem' },
  { label: '情色', value: 'erotic' },
  { label: '国漫', value: 'chinese_anime' },
  { label: '人性', value: 'human_nature' },
  { label: '悬疑', value: 'suspense' },
  { label: '恋爱', value: 'love' },
  { label: '魔幻', value: 'fantasy' },
  { label: '科幻', value: 'sci_fi' },
];

// 剧场版类型选项
const MOVIE_TYPE_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '定格动画', value: 'stop_motion' },
  { label: '传记', value: 'biography' },
  { label: '美国动画', value: 'us_animation' },
  { label: '爱情', value: 'romance' },
  { label: '黑色幽默', value: 'dark_humor' },
  { label: '歌舞', value: 'musical' },
  { label: '儿童', value: 'children' },
  { label: '二次元', value: 'anime' },
  { label: '动物', value: 'animal' },
  { label: '青春', value: 'youth' },
  { label: '历史', value: 'history' },
  { label: '励志', value: 'inspirational' },
  { label: '恶搞', value: 'parody' },
  { label: '治愈', value: 'healing' },
  { label: '运动', value: 'sports' },
  { label: '后宫', value: 'harem' },
  { label: '情色', value: 'erotic' },
  { label: '人性', value: 'human_nature' },
  { label: '悬疑', value: 'suspense' },
  { label: '恋爱', value: 'love' },
  { label: '魔幻', value: 'fantasy' },
  { label: '科幻', value: 'sci_fi' },
];

// 番剧地区选项
const REGION_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '华语', value: 'chinese' },
  { label: '欧美', value: 'western' },
  { label: '国外', value: 'foreign' },
  { label: '韩国', value: 'korean' },
  { label: '日本', value: 'japanese' },
  { label: '中国大陆', value: 'mainland_china' },
  { label: '中国香港', value: 'hong_kong' },
  { label: '美国', value: 'usa' },
  { label: '英国', value: 'uk' },
  { label: '泰国', value: 'thailand' },
  { label: '中国台湾', value: 'taiwan' },
  { label: '意大利', value: 'italy' },
  { label: '法国', value: 'france' },
  { label: '德国', value: 'germany' },
  { label: '西班牙', value: 'spain' },
  { label: '俄罗斯', value: 'russia' },
  { label: '瑞典', value: 'sweden' },
  { label: '巴西', value: 'brazil' },
  { label: '丹麦', value: 'denmark' },
  { label: '印度', value: 'india' },
  { label: '加拿大', value: 'canada' },
  { label: '爱尔兰', value: 'ireland' },
  { label: '澳大利亚', value: 'australia' },
];

// 剧场版地区选项
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

// 年代选项
const YEAR_OPTIONS: SelectorOption[] = [
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

// 平台选项
const PLATFORM_OPTIONS: SelectorOption[] = [
  { label: '全部', value: 'all' },
  { label: '腾讯视频', value: 'tencent' },
  { label: '爱奇艺', value: 'iqiyi' },
  { label: '优酷', value: 'youku' },
  { label: '湖南卫视', value: 'hunan_tv' },
  { label: 'Netflix', value: 'netflix' },
  { label: 'HBO', value: 'hbo' },
  { label: 'BBC', value: 'bbc' },
  { label: 'NHK', value: 'nhk' },
  { label: 'CBS', value: 'cbs' },
  { label: 'NBC', value: 'nbc' },
  { label: 'tvN', value: 'tvn' },
];

// 排序选项
const SORT_OPTIONS: SelectorOption[] = [
  { label: '综合排序', value: 'T' },
  { label: '近期热度', value: 'U' },
  { label: '首映时间', value: 'R' },
  { label: '高分优先', value: 'S' },
];

const COLUMNS = 8;
const CARD_WIDTH = 90; // VideoCard 的宽度
const CARD_MARGIN_RIGHT = 20; // VideoCard 自带的 marginRight
const RENDER_BUFFER = 3; // 当前行上下各保留 3 行，共 7 行
const ROW_GAP = 20; // 行间距
const ROW_HEIGHT = 135 + ROW_GAP; // 行高：135(卡片) + 行间距

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
  onFirstSelectorReady?: (nodeHandle: number | null) => void;
}

const AnimeTab = forwardRef<any, AnimeTabProps>(({ firstSelectorRef, onFirstSelectorReady }, ref) => {
  const [selectedCategory, setSelectedCategory] = useState('new');
  const [selectedWeekday, setSelectedWeekday] = useState(getCurrentWeekday());
  const [focusedCategoryKey, setFocusedCategoryKey] = useState<string | null>(null);
  const [focusedWeekdayKey, setFocusedWeekdayKey] = useState<string | null>(null);
  
  // 番剧和剧场版的筛选状态
  const [focusedFilterKey, setFocusedFilterKey] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState('all');
  const [selectedRegion, setSelectedRegion] = useState('all');
  const [selectedYear, setSelectedYear] = useState('all');
  const [selectedPlatform, setSelectedPlatform] = useState('all');
  const [selectedSort, setSelectedSort] = useState('T');
  
  // 筛选对话框状态
  const [filterDialogVisible, setFilterDialogVisible] = useState(false);
  const [currentFilterType, setCurrentFilterType] = useState<string | null>(null);
  const filterDialogRef = useRef<any>(null);
  const transparentFilterRef = useRef<any>(null);
  
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
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const pageLimit = 25;

  const { scrollViewRef: mainScrollViewRef, isPageFocusedRef } = useScrollContext();
  const { height: screenHeight } = useWindowDimensions();
  const currentRowRef = useRef<number>(-1);
  const [focusedRowIndex, setFocusedRowIndex] = useState(0);
  const rowRefs = useRef<Map<number, any>>(new Map());
  const loadingTriggeredForPageRef = useRef<number>(1);

  // 通知父组件 firstSelector 已准备好
  useEffect(() => {
    if (firstSelectorRef?.current && onFirstSelectorReady) {
      const handle = findNodeHandle(firstSelectorRef.current);
      onFirstSelectorReady(handle);
    }
  }, [firstSelectorRef, onFirstSelectorReady, selectedCategory]);

  // 计算布局参数
  const layout = useMemo(() => calculateLayout(), []);

  // 获取当前筛选状态
  const getCurrentFilterState = useCallback(() => {
    if (selectedCategory === 'new') {
      return `new-${selectedWeekday}`;
    } else if (selectedCategory === 'series') {
      return `series-${selectedType}-${selectedRegion}-${selectedYear}-${selectedPlatform}-${selectedSort}`;
    } else {
      return `movie-${selectedType}-${selectedRegion}-${selectedYear}-${selectedSort}`;
    }
  }, [selectedCategory, selectedWeekday, selectedType, selectedRegion, selectedYear, selectedPlatform, selectedSort]);

  // 加载数据
  const loadData = useCallback(async (isRefresh: boolean = false) => {
    if (isLoading) {
      return;
    }
    if (!isRefresh && !hasMore && selectedCategory !== 'new') {
      return;
    }

    const currentPage = isRefresh ? 0 : page;
    const requestFilterState = getCurrentFilterState();

    setIsLoading(true);
    if (isRefresh) {
      setErrorMessage(null);
    }

    try {
      if (selectedCategory === 'new') {
        // 每日放送：使用 bangumi API
        const result = await bangumiService.getCalendarByWeekday(parseInt(selectedWeekday));

        // 检查当前筛选状态是否仍然与发起请求时一致
        const currentFilterState = getCurrentFilterState();
        if (requestFilterState !== currentFilterState) {
          return;
        }

        if (result.success && result.data) {
          setAnimes(result.data);
        } else {
          setErrorMessage(result.error || '加载失败');
        }
      } else {
        // 番剧或剧场版：使用 douban API
        const typeOption = (selectedCategory === 'series' ? ANIME_TYPE_OPTIONS : MOVIE_TYPE_OPTIONS).find(opt => opt.value === selectedType);
        const regionOption = (selectedCategory === 'series' ? REGION_OPTIONS : MOVIE_REGION_OPTIONS).find(opt => opt.value === selectedRegion);
        const yearOption = YEAR_OPTIONS.find(opt => opt.value === selectedYear);
        const platformOption = PLATFORM_OPTIONS.find(opt => opt.value === selectedPlatform);
        
        const baseParams = selectedCategory === 'series' ? {
          kind: 'tv' as const,
          category: '动画' as const,
          label: selectedType === 'all' ? 'all' : typeOption?.label,
          format: '电视剧' as const,
          region: selectedRegion === 'all' ? 'all' : regionOption?.label,
          year: selectedYear === 'all' ? 'all' : yearOption?.label,
          platform: selectedPlatform === 'all' ? 'all' : platformOption?.label,
          sort: selectedSort,
          pageLimit,
        } : {
          kind: 'movie' as const,
          category: '动画' as const,
          label: selectedType === 'all' ? 'all' : typeOption?.label,
          format: '电影' as const,
          region: selectedRegion === 'all' ? 'all' : regionOption?.label,
          year: selectedYear === 'all' ? 'all' : yearOption?.label,
          sort: selectedSort,
          pageLimit,
        };
        
        let allAnimes: any[] = [];
        
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
            allAnimes = [...result1.data];
          }
          if (result2.success && result2.data) {
            allAnimes = [...allAnimes, ...result2.data];
          }
          
          if (allAnimes.length > 0) {
            setAnimes(allAnimes);
            setPage(2); // 已加载两页，下次从第3页开始
            setHasMore(true);
          } else {
            setAnimes([]);
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
            setAnimes(prev => [...prev, ...result.data!]);
            setPage(currentPage + 1);
            
            if (result.data.length === 0) {
              setHasMore(false);
            }
          } else {
            setErrorMessage(result.error || '加载失败');
          }
        }
      }
    } catch (error) {
      setErrorMessage('加载失败');
    } finally {
      setIsLoading(false);
    }
  }, [isLoading, hasMore, page, selectedCategory, selectedWeekday, selectedType, selectedRegion, selectedYear, selectedPlatform, selectedSort, getCurrentFilterState, pageLimit]);

  // 首次加载
  useEffect(() => {
    loadData(true);
  }, []);

  // 当切换到番剧或剧场版时，重置所有筛选项
  useEffect(() => {
    if (selectedCategory === 'series' || selectedCategory === 'movie') {
      setSelectedType('all');
      setSelectedRegion('all');
      setSelectedYear('all');
      setSelectedPlatform('all');
      setSelectedSort('T');
    }
  }, [selectedCategory]);

  // 当筛选条件改变时，重新加载数据
  useEffect(() => {
    setAnimes([]);
    setPage(1);
    setHasMore(true);
    loadingTriggeredForPageRef.current = 0;
    loadData(true);
  }, [selectedCategory, selectedWeekday, selectedType, selectedRegion, selectedYear, selectedPlatform, selectedSort]);

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

  // 处理番剧/剧场版的筛选项聚焦
  const handleAllFilterFocus = (key: string) => {
    setFocusedFilterKey(key);
    // 滚动到顶部
    if (mainScrollViewRef?.current) {
      mainScrollViewRef.current.scrollTo({ y: 0, animated: true });
    }
    // 重置当前行
    currentRowRef.current = -1;
  };

  // 处理番剧/剧场版的筛选项选择（打开对话框）
  const handleAllFilterSelect = (key: string) => {
    setCurrentFilterType(key);
    setFilterDialogVisible(true);
  };

  // 获取当前筛选对话框的选项和选中值
  const getCurrentFilterOptions = () => {
    const isSeries = selectedCategory === 'series';
    const typeOptions = isSeries ? ANIME_TYPE_OPTIONS : MOVIE_TYPE_OPTIONS;
    const regionOptions = isSeries ? REGION_OPTIONS : MOVIE_REGION_OPTIONS;
    
    switch (currentFilterType) {
      case 'type':
        return { options: typeOptions, selectedValue: selectedType, title: '类型' };
      case 'region':
        return { options: regionOptions, selectedValue: selectedRegion, title: '地区' };
      case 'year':
        return { options: YEAR_OPTIONS, selectedValue: selectedYear, title: '年代' };
      case 'platform':
        return { options: PLATFORM_OPTIONS, selectedValue: selectedPlatform, title: '平台' };
      case 'sort':
        return { options: SORT_OPTIONS, selectedValue: selectedSort, title: '排序' };
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
        setSelectedRegion(value);
        break;
      case 'year':
        setSelectedYear(value);
        break;
      case 'platform':
        setSelectedPlatform(value);
        break;
      case 'sort':
        setSelectedSort(value);
        break;
    }
  };

  // 获取筛选项显示的值
  const getFilterDisplayValue = (key: string) => {
    const isSeries = selectedCategory === 'series';
    const typeOptions = isSeries ? ANIME_TYPE_OPTIONS : MOVIE_TYPE_OPTIONS;
    const regionOptions = isSeries ? REGION_OPTIONS : MOVIE_REGION_OPTIONS;
    
    let value = '';
    let options: SelectorOption[] = [];
    
    switch (key) {
      case 'type':
        value = selectedType;
        options = typeOptions;
        break;
      case 'region':
        value = selectedRegion;
        options = regionOptions;
        break;
      case 'year':
        value = selectedYear;
        options = YEAR_OPTIONS;
        break;
      case 'platform':
        value = selectedPlatform;
        options = PLATFORM_OPTIONS;
        break;
      case 'sort':
        value = selectedSort;
        options = SORT_OPTIONS;
        break;
    }
    
    // 查找对应的 label
    const option = options.find(opt => opt.value === value);
    return option?.label || '';
  };

  const handleCardFocus = (rowIndex: number) => {
    // 先更新聚焦行索引（用于虚拟化），确保目标行已渲染
    setFocusedRowIndex(rowIndex);

    // 判断是否需要加载下一页（仅对番剧和剧场版）
    const shouldLoadNextPage = selectedCategory !== 'new' && rowIndex === rows.length - 1 && hasMore && !isLoading && loadingTriggeredForPageRef.current < page;

    // 当切换到新行时，滚动到居中位置
    // 但如果页面失去焦点，不执行滚动（避免导航时的焦点转移触发滚动）
    if (currentRowRef.current !== rowIndex && mainScrollViewRef?.current && isPageFocusedRef?.current) {
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
                  }, 300);
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
  const bottomPadding = Math.max(0, (screenHeight / 2) - 67.5); // 67.5 是卡片高度的一半 (135/2)

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
              series: transparentFilterRef?.current?.getFirstOptionRef?.(),
              movie: transparentFilterRef?.current?.getFirstOptionRef?.(),
            }}
          />
        </View>

        {/* 根据选中的分类显示不同的筛选行 */}
        {selectedCategory === 'new' ? (
          // 每日放送：显示星期筛选
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
        ) : (
          // 番剧或剧场版：显示透明筛选器
          <View style={styles.filterRow}>
            <Text style={styles.filterLabel}>筛选</Text>
            <TransparentFilterSelector
              ref={transparentFilterRef}
              options={(selectedCategory === 'series' ? SERIES_FILTER_OPTIONS : MOVIE_FILTER_OPTIONS).map(opt => ({
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
              const shouldShowSkeletons = isLastRow && isLoading && row.length < COLUMNS && selectedCategory !== 'new';
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
                  {row.map((item, colIndex) => {
                    // 根据数据来源转换为 VideoInfo
                    const videoInfo = selectedCategory === 'new' 
                      ? bangumiItemToVideoInfo(item)
                      : doubanMovieToVideoInfo(item);
                    
                    // 根据一级分类设置 from 参数
                    const from = selectedCategory === 'new' ? 'bangumi' : 'douban';
                    
                    return (
                      <View 
                        key={item.id}
                        style={[
                          styles.cardWrapper,
                          colIndex < COLUMNS - 1 ? { marginRight: layout.cardGap - CARD_MARGIN_RIGHT } : { marginRight: -CARD_MARGIN_RIGHT }
                        ]}
                      >
                        <VideoCard
                          videoInfo={videoInfo}
                          from={from}
                          onPress={() => console.log('Pressed:', videoInfo.title)}
                          onFocus={() => handleCardFocus(rowIndex)}
                          nextFocusUp={rowIndex === 0 ? (weekdaySelectorHandle ?? undefined) : undefined}
                        />
                      </View>
                    );
                  })}
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
            {isLoading && (rows.length === 0 || (rows[rows.length - 1].length === COLUMNS && selectedCategory !== 'new')) && (
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
    paddingTop: 11,
    paddingBottom: 18,
    gap: 9,
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 0,
  },
  filterLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: '#ffffff',
    width: 36,
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
