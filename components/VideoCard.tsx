import { StyleSheet, View, Text, Pressable, Animated, Easing, useTVEventHandler } from 'react-native';
import { useRef, useEffect, useState } from 'react';
import FastImage from 'react-native-fast-image';

interface VideoCardProps {
  title: string;
  coverUrl: string;
  onPress?: () => void;
  onFocus?: (cardRef: any) => void;
  onBlur?: () => void;
  onNavigateVertical?: (direction: 'up' | 'down') => void;
  nextFocusUp?: number;
  nextFocusDown?: number;
}

export default function VideoCard({ title, coverUrl, onPress, onFocus: onFocusProp, onBlur: onBlurProp, onNavigateVertical, nextFocusUp, nextFocusDown }: VideoCardProps) {
  const scrollAnim = useRef(new Animated.Value(0)).current;
  const scaleAnim = useRef(new Animated.Value(1)).current;
  const cardRef = useRef<any>(null);
  const [isFocused, setIsFocused] = useState(false);
  const [titleWidth, setTitleWidth] = useState(0);
  const [containerWidth, setContainerWidth] = useState(0);
  const [isMeasured, setIsMeasured] = useState(false);
  const [measureKey, setMeasureKey] = useState(0);
  const [imageError, setImageError] = useState(false);
  const needsScroll = titleWidth > containerWidth && containerWidth > 0;

  useEffect(() => {
    scrollAnim.setValue(0);
  }, []);

  const startScrollAnimation = () => {
    if (!needsScroll) return;

    // 循环距离：文本宽度 + 间距
    const gap = 30; // 两个文本之间的间距
    const loopDistance = titleWidth + gap;
    
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
  };

  const stopScrollAnimation = () => {
    scrollAnim.stopAnimation();
    Animated.timing(scrollAnim, {
      toValue: 0,
      duration: 300,
      useNativeDriver: true,
    }).start();
  };

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
    Animated.timing(scaleAnim, {
      toValue: 1.08,
      duration: 100,
      useNativeDriver: true,
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
  }, [isFocused, isMeasured, needsScroll]);

  const handleBlur = () => {
    setIsFocused(false);
    Animated.timing(scaleAnim, {
      toValue: 1,
      duration: 100,
      useNativeDriver: true,
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

  return (
    <>
      <Animated.View
        ref={cardRef}
        style={[
          styles.container,
          { transform: [{ scale: scaleAnim }] },
        ]}
      >
        <Pressable
          style={styles.pressable}
          onPress={onPress}
          onFocus={handleFocus}
          onBlur={handleBlur}
          nextFocusUp={nextFocusUp}
          nextFocusDown={nextFocusDown}
        >
          <View style={styles.cover}>
            <FastImage 
              source={{ 
                uri: coverUrl,
                headers: {
                  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
                  'Referer': 'https://movie.douban.com/',
                },
                priority: FastImage.priority.normal,
              }} 
              style={styles.coverImage}
              resizeMode={FastImage.resizeMode.cover}
              onError={() => {
                setImageError(true);
              }}
            />
            {imageError && (
              <View style={styles.errorOverlay}>
                <Text style={styles.errorText}>加载失败</Text>
              </View>
            )}
          </View>
          <View 
            style={styles.content}
            onLayout={(e) => setContainerWidth(e.nativeEvent.layout.width)}
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
                          left: titleWidth + 30,
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
          </View>
        </Pressable>
      </Animated.View>
      {/* 隐藏的文本用于预测量完整宽度 - 始终渲染 */}
      <View 
        key={measureKey}
        style={styles.measureContainer}
        onLayout={(e) => {
          const width = e.nativeEvent.layout.width;
          setTitleWidth(width);
          setIsMeasured(true);
        }}
      >
        <Text style={styles.title}>
          {title}
        </Text>
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  container: {
    width: 120,
    marginRight: 20,
  },
  pressable: {
    width: '100%',
  },
  cover: {
    width: 120,
    height: 180,
    borderRadius: 6,
    backgroundColor: '#2a2a2a',
    overflow: 'hidden',
  },
  coverImage: {
    width: '100%',
    height: '100%',
  },
  content: {
    marginTop: 6,
    width: '100%',
  },
  titleContainer: {
    overflow: 'hidden',
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  titleContainerScrolling: {
    alignItems: 'flex-start',
    justifyContent: 'flex-start',
  },
  title: {
    fontSize: 12,
    fontWeight: '500',
    color: '#ffffff',
    textAlign: 'center',
  },
  titleScrolling: {
    position: 'absolute',
    width: 500,
  },
  measureContainer: {
    position: 'absolute',
    opacity: 0,
    left: 0,
    top: 0,
    zIndex: -999,
    pointerEvents: 'none',
    flexDirection: 'row',
    maxWidth: 1000,
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
    fontSize: 10,
    textAlign: 'center',
  },
});
