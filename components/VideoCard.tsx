import { StyleSheet, View, Text, Pressable, Animated, Easing, useTVEventHandler } from 'react-native';
import { useRef, useEffect, useState, memo, useCallback } from 'react';
import FastImage from 'react-native-fast-image';
import { useScrollContext } from '@/contexts/ScrollContext';
import { VideoInfo } from '@/types/entities';
import { router } from 'expo-router';

interface VideoCardProps {
  videoInfo: VideoInfo;
  from?: string;
  onPress?: () => void;
  onFocus?: (cardRef: any) => void;
  onBlur?: () => void;
  onNavigateVertical?: (direction: 'up' | 'down') => void;
  nextFocusUp?: number;
  nextFocusDown?: number;
  isSelected?: boolean;
  style?: any;
}

function VideoCard({ videoInfo, from, onPress, onFocus: onFocusProp, onBlur: onBlurProp, onNavigateVertical, nextFocusUp, nextFocusDown, isSelected = false, style }: VideoCardProps) {
  const { title, cover: coverUrl, sourceName, index, totalEpisodes, rate, playTime, totalTime, year } = videoInfo;
  const showSourceName = (from === 'playrecord' || from === 'favorite' || from === 'source') && sourceName;
  const showEpisodeBadge = (from === 'playrecord' || from === 'favorite' || from === 'search' || from === 'source') && totalEpisodes > 1;
  const showRateBadge = (from === 'bangumi' || from === 'douban') && rate && rate !== '0';
  const showYearBadge = from === 'search' && year && year !== '0' && year.toLowerCase() !== 'unknown' && year.trim() !== '';
  const showProgressBar = (from === 'playrecord' || from === 'source') && totalTime > 0;
  const progressPercentage = showProgressBar ? Math.min((playTime / totalTime) * 100, 100) : 0;

  // 徽章文本逻辑
  const getBadgeText = () => {
    if (from === 'playrecord') {
      return `${index}/${totalEpisodes}`;
    }
    if (from === 'favorite' || from === 'source') {
      return index > 0 ? `${index}/${totalEpisodes}` : `${totalEpisodes}`;
    }
    if (from === 'search') {
      return `${totalEpisodes}`;
    }
    return '';
  };

  // 格式化评分，保留一位小数
  const getFormattedRate = () => {
    if (!rate) return '';
    const numRate = parseFloat(rate);
    if (isNaN(numRate)) return rate;
    return numRate.toFixed(1);
  };
  const scrollAnim = useRef(new Animated.Value(0)).current;
  const scaleAnim = useRef(new Animated.Value(1)).current;
  const cardRef = useRef<any>(null);
  const [isFocused, setIsFocused] = useState(false);
  const [titleWidth, setTitleWidth] = useState(0);
  const [containerWidth, setContainerWidth] = useState(0);
  const [isMeasured, setIsMeasured] = useState(false);
  const [measureKey, setMeasureKey] = useState(0);
  const needsScroll = titleWidth > containerWidth && containerWidth > 0;
  const measureTextRef = useRef<Text>(null);
  const { renderMeasureElement, clearMeasureElement } = useScrollContext();

  // 固定的文本间隔
  const TEXT_GAP = 40;

  // 使用 ref 存储最新的 titleWidth，避免闭包问题
  const titleWidthRef = useRef(titleWidth);
  useEffect(() => {
    titleWidthRef.current = titleWidth;
  }, [titleWidth]);

  useEffect(() => {
    scrollAnim.setValue(0);
  }, []);

  const startScrollAnimation = useCallback(() => {
    if (!needsScroll) return;

    // 循环距离：文本宽度 + 固定间距（使用 ref 获取最新值）
    const loopDistance = titleWidthRef.current + TEXT_GAP;

    // 先延迟1秒
    Animated.sequence([
      Animated.delay(1000),
      Animated.loop(
        Animated.timing(scrollAnim, {
          toValue: -loopDistance,
          duration: loopDistance * 25, // 恒定速度
          easing: Easing.linear, // 线性匀速
          useNativeDriver: true,
        })
      ),
    ]).start();
  }, [needsScroll, scrollAnim, TEXT_GAP, title, containerWidth]);

  const stopScrollAnimation = useCallback(() => {
    scrollAnim.stopAnimation();
    Animated.timing(scrollAnim, {
      toValue: 0,
      duration: 300,
      useNativeDriver: true,
    }).start();
  }, [scrollAnim]);

  // 当聚焦时，在全局测量容器中渲染测量元素
  useEffect(() => {
    // 只在聚焦时才进行测量
    if (!isFocused) {
      return;
    }

    const measureEl = (
      <Text
        key={measureKey}
        ref={measureTextRef}
        style={styles.title}
        onLayout={(e) => {
          const width = e.nativeEvent.layout.width;
          setTitleWidth(width);
          setIsMeasured(true);
        }}
      >
        {title}
      </Text>
    );
    renderMeasureElement(measureEl);

    return () => {
      clearMeasureElement();
    };
  }, [isFocused, measureKey, title, renderMeasureElement, clearMeasureElement]);

  const handleFocus = () => {
    // 先停止所有动画，防止快速移动时的状态冲突
    scrollAnim.stopAnimation(() => {
      // 在停止后立即重置到0
      scrollAnim.setValue(0);
    });
    // 重置测量状态，强制重新测量
    setIsMeasured(false);
    setMeasureKey(prev => prev + 1);
    setIsFocused(true);
    Animated.spring(scaleAnim, {
      toValue: 1.1,
      useNativeDriver: true,
      friction: 8,
      tension: 100,
    }).start();
    onFocusProp?.(cardRef.current);
  };

  // 当测量完成后启动动画
  useEffect(() => {

    if (isFocused && isMeasured && needsScroll) {
      // 停止之前的动画并重置
      scrollAnim.stopAnimation(() => {
        scrollAnim.setValue(0);
        startScrollAnimation();
      });
    }
  }, [isFocused, isMeasured, needsScroll, titleWidth]);

  const handleBlur = () => {
    setIsFocused(false);
    Animated.spring(scaleAnim, {
      toValue: 1,
      useNativeDriver: true,
      friction: 8,
      tension: 100,
    }).start();
    stopScrollAnimation();
    onBlurProp?.();
  };

  // 监听 TV 遥控器事件
  useTVEventHandler((evt: any) => {
    const eventType = evt?.eventType;
    // 按上或按下时，传递方向信息给父组件
    if (eventType === 'up') {
      onNavigateVertical?.('up');
    } else if (eventType === 'down') {
      onNavigateVertical?.('down');
    }
  });

  // 处理点击事件
  const handlePress = () => {
    // 如果来源是 douban，导航到详情页
    if (from === 'douban' && videoInfo.doubanId) {
      router.push({
        pathname: '/detail' as any,
        params: {
          title: videoInfo.title,
          doubanId: videoInfo.doubanId,
          from: from,
          year: videoInfo.year,
        },
      });
    } else if (from === 'bangumi' && videoInfo.bangumiId) {
      // 如果来源是 bangumi，导航到详情页
      router.push({
        pathname: '/detail' as any,
        params: {
          title: videoInfo.title,
          bangumiId: videoInfo.bangumiId.toString(),
          from: from,
          year: videoInfo.year,
        },
      });
    } else if (onPress) {
      // 否则调用传入的 onPress
      onPress();
    }
  };

  return (
    <>
      <Animated.View
        ref={cardRef}
        style={[
          styles.container,
          { transform: [{ scale: scaleAnim }] },
          style,
        ]}
      >
        <Pressable
          style={styles.pressable}
          onPress={handlePress}
          onFocus={handleFocus}
          onBlur={handleBlur}
          nextFocusUp={nextFocusUp}
          nextFocusDown={nextFocusDown}
        >
          <View style={styles.coverWrapper}>
            {isSelected && (
              <View style={styles.selectedIndicator}>
                <Text style={styles.selectedIndicatorIcon}>✓</Text>
              </View>
            )}

            <View style={[
              styles.cover,
              isFocused && styles.coverFocused
            ]}>
              <FastImage
              source={{
                uri: coverUrl,
                headers: {
                  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
                  'Referer': 'https://movie.douban.com/',
                },
                priority: FastImage.priority.normal,
              }}
              style={[styles.coverImage, isFocused && styles.coverImageFocused]}
              resizeMode={FastImage.resizeMode.cover}
              onError={() => {
                // setImageError(true);
              }}
            />
            {showYearBadge && (
              <View style={styles.yearBadge}>
                <Text style={styles.yearBadgeText}>{year}</Text>
              </View>
            )}
            {showEpisodeBadge && (
              <View style={styles.episodeBadge}>
                <Text style={styles.episodeBadgeText}>{getBadgeText()}</Text>
              </View>
            )}
            {showRateBadge && (
              <View style={styles.rateBadge}>
                <Text style={styles.rateBadgeText}>{getFormattedRate()}</Text>
              </View>
            )}
            {showProgressBar && (
              <View style={styles.progressBarContainer}>
                <View style={styles.progressBarBackground}>
                  <View style={[styles.progressBarFill, { width: `${progressPercentage}%` }]} />
                </View>
              </View>
            )}
            </View>
          </View>
          <View
            style={styles.content}
            onLayout={(e) => {
              // 只在第一次或聚焦时测量容器宽度
              if (containerWidth === 0 || isFocused) {
                const width = e.nativeEvent.layout.width;
                setContainerWidth(width);
              }
            }}
          >
            <View style={[
              styles.titleContainer,
              (isFocused && needsScroll) && styles.titleContainerScrolling
            ]}>
              {isFocused && isMeasured ? (
                needsScroll ? (
                  <>
                    {/* 第一个文本实例 */}
                    <Animated.Text
                      style={[
                        styles.title,
                        styles.titleScrolling,
                        {
                          textAlign: 'left',
                          transform: [{ translateX: scrollAnim }],
                          left: 0,
                          width: titleWidth,
                        },
                      ]}
                    >
                      {title}
                    </Animated.Text>
                    {/* 第二个文本实例，跟在第一个后面 */}
                    <Animated.View
                      style={[
                        styles.titleScrolling,
                        {
                          transform: [{ translateX: scrollAnim }],
                          left: titleWidth + TEXT_GAP,
                          width: titleWidth,
                        },
                      ]}
                    >
                      <Text style={[styles.title, { textAlign: 'left' }]}>
                        {title}
                      </Text>
                    </Animated.View>
                  </>
                ) : (
                  <Text style={styles.title}>
                    {title}
                  </Text>
                )
              ) : (
                <Text style={styles.title} numberOfLines={1}>
                  {title}
                </Text>
              )}
            </View>
            {showSourceName && (
              <View style={styles.sourceNameContainer}>
                <Text style={styles.sourceName}>{sourceName}</Text>
              </View>
            )}
          </View>
        </Pressable>
      </Animated.View>
    </>
  );
}

const styles = StyleSheet.create({
  container: {
    width: 90,
    marginRight: 20,
  },
  pressable: {
    width: '100%',
  },
  coverWrapper: {
    position: 'relative',
    width: 90,
    height: 135,
  },
  cover: {
    width: 90,
    height: 135,
    borderRadius: 6,
    backgroundColor: '#2a2a2a',
  },
  coverFocused: {
    borderWidth: 2,
    borderColor: '#ffffff',
  },
  selectedIndicator: {
    position: 'absolute',
    top: -4,
    left: -4,
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: '#4CAF50',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 2,
    borderColor: '#0a0a0a',
    zIndex: 10,
  },
  selectedIndicatorIcon: {
    fontSize: 10,
    color: '#ffffff',
    fontWeight: '700',
  },
  coverImage: {
    width: 90,
    height: 135,
    borderRadius: 6,
  },
  coverImageFocused: {
    width: 86,
    height: 131,
    borderRadius: 4,
  },
  content: {
    marginTop: 6,
    width: '100%',
  },
  titleContainer: {
    overflow: 'hidden',
    height: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  titleContainerScrolling: {
    alignItems: 'flex-start',
    justifyContent: 'flex-start',
  },
  title: {
    fontSize: 11,
    fontWeight: '500',
    color: '#ffffff',
    textAlign: 'center',
  },
  titleScrolling: {
    position: 'absolute',
    // width 将通过内联样式动态设置
  },
  errorOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorText: {
    color: '#ff6b6b',
    fontSize: 9,
    textAlign: 'center',
  },
  sourceNameContainer: {
    marginTop: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sourceName: {
    fontSize: 8,
    color: 'rgba(255, 255, 255, 0.5)',
    textAlign: 'center',
    borderWidth: 0.5,
    borderColor: 'rgba(255, 255, 255, 0.3)',
    borderRadius: 3,
    paddingHorizontal: 3,
    paddingVertical: 1,
  },
  yearBadge: {
    position: 'absolute',
    top: 3,
    left: 3,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    borderRadius: 3,
    paddingHorizontal: 5,
    paddingVertical: 1.5,
  },
  yearBadgeText: {
    fontSize: 9,
    fontWeight: '600',
    color: '#ffffff',
  },
  episodeBadge: {
    position: 'absolute',
    top: 3,
    right: 3,
    backgroundColor: '#4CAF50',
    borderRadius: 3,
    paddingHorizontal: 5,
    paddingVertical: 1.5,
  },
  episodeBadgeText: {
    fontSize: 9,
    fontWeight: '600',
    color: '#ffffff',
  },
  rateBadge: {
    position: 'absolute',
    top: 3,
    right: 3,
    backgroundColor: '#E91E63',
    borderRadius: 11,
    width: 22,
    height: 22,
    justifyContent: 'center',
    alignItems: 'center',
  },
  rateBadgeText: {
    fontSize: 9,
    fontWeight: '700',
    color: '#ffffff',
  },
  progressBarContainer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 3,
    borderBottomLeftRadius: 6,
    borderBottomRightRadius: 6,
    overflow: 'hidden',
  },
  progressBarBackground: {
    flex: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.3)', // 半透明白色 - 未播放部分
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#4CAF50', // 绿色 - 已播放部分（与集数徽章相同）
  },
});

// 使用 memo 优化，只在 props 变化时重新渲染
export default memo(VideoCard, (prevProps, nextProps) => {
  // 返回 true 表示 props 相同，跳过渲染
  // 返回 false 表示 props 不同，需要重新渲染
  return (
    prevProps.videoInfo.id === nextProps.videoInfo.id &&
    prevProps.videoInfo.title === nextProps.videoInfo.title &&
    prevProps.videoInfo.cover === nextProps.videoInfo.cover &&
    prevProps.videoInfo.source === nextProps.videoInfo.source &&
    prevProps.nextFocusUp === nextProps.nextFocusUp &&
    prevProps.nextFocusDown === nextProps.nextFocusDown &&
    prevProps.isSelected === nextProps.isSelected
    // 注意：onPress、onFocus、onBlur 等函数引用可能每次都不同
    // 但它们的行为通常是一致的，所以我们不比较它们
  );
});
