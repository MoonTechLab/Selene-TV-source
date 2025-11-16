import { StyleSheet, Text, Pressable, Animated, TVFocusGuideView, findNodeHandle } from 'react-native';
import { useRef, useEffect, useState, forwardRef, useImperativeHandle } from 'react';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

interface FilterOption {
  key: string;
  label: string;
}

interface FilterSelectorProps {
  options: FilterOption[];
  selectedKey: string;
  focusedKey: string | null;
  onFocus: (key: string) => void;
  onBlur: () => void;
  onSelect: (key: string) => void;
  nextFocusUp?: any;
  nextFocusDownMap?: Record<string, any>; // 为每个选项指定不同的 nextFocusDown
}

const FilterSelector = forwardRef<any, FilterSelectorProps>((props, ref) => {
  const { options, selectedKey, focusedKey, onFocus, onBlur, onSelect, nextFocusUp, nextFocusDownMap } = props;
  
  // 为每个选项创建 ref
  const optionRefs = useRef<Record<string, any>>({});
  const [preferredFocusTarget, setPreferredFocusTarget] = useState<number | null>(null);
  const [nextFocusUpHandle, setNextFocusUpHandle] = useState<number | null>(null);
  const [nextFocusDownHandles, setNextFocusDownHandles] = useState<Record<string, number | null>>({});

  // 暴露当前选中项的引用给父组件
  useImperativeHandle(ref, () => {
    return optionRefs.current[selectedKey] || null;
  }, [selectedKey]);
  
  // 为每个选项创建动画值
  const optionAnimations = useRef(
    options.reduce((acc, option) => {
      acc[option.key] = new Animated.Value(1);
      return acc;
    }, {} as Record<string, Animated.Value>)
  ).current;

  // 处理焦点变化时的动画
  useEffect(() => {
    options.forEach((option) => {
      const isFocused = focusedKey === option.key;
      Animated.spring(optionAnimations[option.key], {
        toValue: isFocused ? 1.05 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedKey]);

  // 当选中项改变时，更新首选焦点目标
  useEffect(() => {
    const selectedRef = optionRefs.current[selectedKey];
    if (selectedRef) {
      const handle = findNodeHandle(selectedRef);
      setPreferredFocusTarget(handle);
    }
  }, [selectedKey]);

  // 当 nextFocusUp 改变时，获取其节点句柄
  useEffect(() => {
    if (nextFocusUp) {
      const handle = findNodeHandle(nextFocusUp);
      setNextFocusUpHandle(handle);
    } else {
      setNextFocusUpHandle(null);
    }
  }, [nextFocusUp]);

  // 当 nextFocusDownMap 改变时，获取各个选项的 nextFocusDown 节点句柄
  useEffect(() => {
    if (nextFocusDownMap) {
      const handles: Record<string, number | null> = {};
      Object.keys(nextFocusDownMap).forEach((key) => {
        const target = nextFocusDownMap[key];
        if (target) {
          const handle = findNodeHandle(target);
          handles[key] = handle;
        } else {
          handles[key] = null;
        }
      });
      setNextFocusDownHandles(handles);
    } else {
      setNextFocusDownHandles({});
    }
  }, [nextFocusDownMap]);

  return (
    <TVFocusGuideView 
      style={styles.container}
      destinations={preferredFocusTarget ? [preferredFocusTarget] : undefined}
    >
      {options.map((option) => {
        const isSelected = selectedKey === option.key;
        const isFocused = focusedKey === option.key;
        
        return (
          <AnimatedPressable
            key={option.key}
            ref={(el) => {
              optionRefs.current[option.key] = el;
            }}
            style={[
              styles.option,
              isSelected && !isFocused && styles.optionSelected,
              isFocused && styles.optionFocused,
              {
                transform: [{ scale: optionAnimations[option.key] }],
              }
            ]}
            onFocus={() => onFocus(option.key)}
            onBlur={onBlur}
            onPress={() => onSelect(option.key)}
            nextFocusUp={nextFocusUpHandle}
            nextFocusDown={nextFocusDownHandles[option.key] ?? undefined}
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
  );
});

FilterSelector.displayName = 'FilterSelector';

export default FilterSelector;

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderRadius: 18,
    padding: 2,
    gap: 4,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    height: 28,
    paddingHorizontal: 12,
    borderRadius: 14,
  },
  optionSelected: {
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
  },
  optionFocused: {
    backgroundColor: '#ffffff',
  },
  optionText: {
    fontSize: 11,
    fontWeight: '600',
    color: '#ffffff',
  },
  optionTextFocused: {
    color: '#0a0a0a',
  },
});
