import { StyleSheet, Text, Pressable, Modal, Animated, TVFocusGuideView, findNodeHandle, ScrollView, useWindowDimensions } from 'react-native';
import { useRef, useEffect, useState, forwardRef, useImperativeHandle } from 'react';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

export interface SelectorOption {
  label: string;
  value: string;
}

interface FilterDialogProps {
  visible: boolean;
  title: string;
  options: SelectorOption[];
  selectedValue: string;
  onSelect: (value: string) => void;
  onClose: () => void;
}

const FilterDialog = forwardRef<any, FilterDialogProps>((props, ref) => {
  const { visible, title, options, selectedValue, onSelect, onClose } = props;
  const [focusedValue, setFocusedValue] = useState<string | null>(null);
  const optionRefs = useRef<Record<string, any>>({});
  const [preferredFocusTarget, setPreferredFocusTarget] = useState<number | null>(null);
  const [modalVisible, setModalVisible] = useState(false);
  const { height: screenHeight, width: screenWidth } = useWindowDimensions();
  const scrollViewRef = useRef<ScrollView>(null);
  const optionsContainerRef = useRef<any>(null);
  
  // 对话框弹出动画
  const dialogScale = useRef(new Animated.Value(0.8)).current;
  const dialogOpacity = useRef(new Animated.Value(0)).current;
  const overlayOpacity = useRef(new Animated.Value(0)).current;
  
  // 为每个选项创建动画值
  const optionAnimations = useRef<Record<string, Animated.Value>>({});
  
  // 初始化动画值
  useEffect(() => {
    options.forEach((option) => {
      if (!optionAnimations.current[option.value]) {
        optionAnimations.current[option.value] = new Animated.Value(1);
      }
    });
  }, [options]);

  // 暴露方法给父组件
  useImperativeHandle(ref, () => ({
    focusFirstOption: () => {
      const firstOption = options[0];
      if (firstOption && optionRefs.current[firstOption.value]) {
        optionRefs.current[firstOption.value].focus();
      }
    }
  }), [options]);

  // 当对话框打开/关闭时，执行弹出动画
  useEffect(() => {
    if (visible) {
      setModalVisible(true);
      // 弹出动画
      Animated.parallel([
        Animated.spring(dialogScale, {
          toValue: 1,
          useNativeDriver: true,
          friction: 10,
          tension: 150,
        }),
        Animated.spring(dialogOpacity, {
          toValue: 1,
          useNativeDriver: true,
          friction: 10,
          tension: 150,
        }),
        Animated.spring(overlayOpacity, {
          toValue: 1,
          useNativeDriver: true,
          friction: 10,
          tension: 150,
        }),
      ]).start();
      
      // 优先聚焦到选中项，如果没有选中项则聚焦第一项
      const targetValue = selectedValue || options[0]?.value;
      const targetRef = optionRefs.current[targetValue];
      if (targetRef) {
        const handle = findNodeHandle(targetRef);
        setPreferredFocusTarget(handle);
        // 延迟聚焦，确保 Modal 完全渲染
        setTimeout(() => {
          targetRef.focus?.();
        }, 100);
      }
    } else if (modalVisible) {
      // 隐藏动画 - 快速缩小到 0.8
      Animated.parallel([
        Animated.spring(dialogScale, {
          toValue: 0.8,
          useNativeDriver: true,
          friction: 12,
          tension: 200,
        }),
        Animated.spring(dialogOpacity, {
          toValue: 0,
          useNativeDriver: true,
          friction: 12,
          tension: 200,
        }),
        Animated.spring(overlayOpacity, {
          toValue: 0,
          useNativeDriver: true,
          friction: 12,
          tension: 200,
        }),
      ]).start(() => {
        // 动画完成后关闭 Modal
        setModalVisible(false);
        setFocusedValue(null);
      });
    }
  }, [visible, selectedValue, options, dialogScale, dialogOpacity, modalVisible]);

  // 处理焦点变化时的动画和滚动
  useEffect(() => {
    options.forEach((option) => {
      const animation = optionAnimations.current[option.value];
      if (animation) {
        const isFocused = focusedValue === option.value;
        Animated.spring(animation, {
          toValue: isFocused ? 1.05 : 1,
          useNativeDriver: true,
          friction: 8,
          tension: 100,
        }).start();
      }
    });

    // 当焦点改变时，滚动使聚焦项居中
    if (focusedValue && scrollViewRef.current && optionsContainerRef.current) {
      const focusedRef = optionRefs.current[focusedValue];
      if (focusedRef) {
        // 延迟测量，确保布局已完成
        requestAnimationFrame(() => {
          focusedRef.measureLayout(
            optionsContainerRef.current,
            (_x: number, y: number, _width: number, height: number) => {
              // 计算滚动位置，使聚焦项居中
              const scrollViewHeight = screenHeight * 0.6 - 19.4 * 2 - 14.6 - 13; // 减去 padding 和 title
              const scrollToY = Math.max(0, y - (scrollViewHeight / 2) + (height / 2));
              
              scrollViewRef.current?.scrollTo({ y: scrollToY, animated: true });
            },
            () => {
              // 测量失败时的回调
            }
          );
        });
      }
    }
  }, [focusedValue, options, screenHeight]);

  const handleSelect = (value: string) => {
    onSelect(value);
    onClose();
  };

  return (
    <Modal
      visible={modalVisible}
      transparent
      animationType="none"
      onRequestClose={onClose}
    >
      <Animated.View style={[styles.overlay, { opacity: overlayOpacity }]}>
        <Pressable 
          style={styles.dialogWrapper}
          onPress={(e) => {
            // 阻止事件冒泡，防止点击对话框内容时关闭
            e.stopPropagation();
          }}
        >
          <Animated.View 
            style={[
              styles.dialog,
              {
                transform: [{ scale: dialogScale }],
                opacity: dialogOpacity,
                maxHeight: screenHeight * 0.75,
                minHeight: screenHeight * 0.3,
                width: screenWidth * 0.6,
              }
            ]}
          >
            <Text style={styles.title}>{title}</Text>
            <ScrollView 
              ref={scrollViewRef}
              style={styles.scrollView}
              contentContainerStyle={styles.scrollContent}
              showsVerticalScrollIndicator={true}
            >
              <TVFocusGuideView 
                ref={optionsContainerRef}
                style={styles.optionsContainer}
                destinations={preferredFocusTarget ? [preferredFocusTarget] : undefined}
                autoFocus
              >
                {options.map((option) => {
                  const isSelected = selectedValue === option.value;
                  const isFocused = focusedValue === option.value;
                  const animation = optionAnimations.current[option.value] || new Animated.Value(1);
                  
                  return (
                    <AnimatedPressable
                      key={option.value}
                      ref={(el) => {
                        optionRefs.current[option.value] = el;
                      }}
                      style={[
                        styles.option,
                        isSelected && !isFocused && styles.optionSelected,
                        isFocused && styles.optionFocused,
                        {
                          transform: [{ scale: animation }],
                        }
                      ]}
                      onFocus={() => setFocusedValue(option.value)}
                      onBlur={() => setFocusedValue(null)}
                      onPress={() => handleSelect(option.value)}
                      hasTVPreferredFocus={option.value === (selectedValue || options[0]?.value)}
                    >
                      <Text style={[
                        styles.optionText,
                        isFocused && styles.optionTextFocused
                      ]}>
                        {option.label}
                      </Text>
                    </AnimatedPressable>
                  );
                })}
              </TVFocusGuideView>
            </ScrollView>
          </Animated.View>
        </Pressable>
      </Animated.View>
    </Modal>
  );
});

FilterDialog.displayName = 'FilterDialog';

export default FilterDialog;

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  dialogWrapper: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  dialog: {
    backgroundColor: '#1a1a1a',
    borderRadius: 9.7,
    paddingTop: 19.4,
    paddingLeft: 19.4,
    paddingRight: 19.4,
    maxWidth: 486,
  },
  title: {
    fontSize: 14.6,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 13,
  },
  scrollView: {
    maxHeight: '100%',
  },
  scrollContent: {
    paddingBottom: 19.4,
  },
  optionsContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    padding: 2.6,
    gap: 7.8,
  },
  option: {
    paddingHorizontal: 13,
    paddingVertical: 6.5,
    borderRadius: 6.5,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  optionSelected: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
  },
  optionFocused: {
    backgroundColor: '#ffffff',
  },
  optionText: {
    fontSize: 11.3,
    fontWeight: '600',
    color: '#ffffff',
  },
  optionTextFocused: {
    color: '#0a0a0a',
  },
});
