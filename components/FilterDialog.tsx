import { StyleSheet, View, Text, Pressable, Modal, Animated, TVFocusGuideView, findNodeHandle } from 'react-native';
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

  // 处理焦点变化时的动画
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
  }, [focusedValue, options]);

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
              }
            ]}
          >
            <Text style={styles.title}>{title}</Text>
            <TVFocusGuideView 
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
    borderRadius: 12,
    padding: 24,
    minWidth: 400,
    maxWidth: 600,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 16,
  },
  optionsContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  option: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  optionSelected: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
  },
  optionFocused: {
    backgroundColor: '#ffffff',
  },
  optionText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ffffff',
  },
  optionTextFocused: {
    color: '#0a0a0a',
  },
});
