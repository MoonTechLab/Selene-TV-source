import { StyleSheet, Text, Pressable, Animated, TVFocusGuideView, findNodeHandle } from 'react-native';
import { useRef, useEffect, useState, forwardRef, useImperativeHandle } from 'react';
import { ChevronDown } from 'lucide-react-native';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

interface FilterOption {
  key: string;
  label: string;
  selectedValue?: string; // 显示选中的值
}

interface TransparentFilterSelectorProps {
  options: FilterOption[];
  focusedKey: string | null;
  onFocus: (key: string) => void;
  onBlur: () => void;
  onSelect: (key: string) => void;
  nextFocusUp?: any;
}

const TransparentFilterSelector = forwardRef<any, TransparentFilterSelectorProps>((props, ref) => {
  const { options, focusedKey, onFocus, onBlur, onSelect, nextFocusUp } = props;
  
  // 为每个选项创建 ref
  const optionRefs = useRef<Record<string, any>>({});
  const [nextFocusUpHandle, setNextFocusUpHandle] = useState<number | null>(null);

  // 暴露第一个选项的引用给父组件
  useImperativeHandle(ref, () => {
    return optionRefs.current[options[0]?.key] || null;
  }, [options]);
  
  // 为每个选项创建动画值
  const optionAnimations = useRef<Record<string, Animated.Value>>({});
  
  // 确保每个选项都有动画值
  useEffect(() => {
    options.forEach((option) => {
      if (!optionAnimations.current[option.key]) {
        optionAnimations.current[option.key] = new Animated.Value(1);
      }
    });
  }, [options]);

  // 处理焦点变化时的动画
  useEffect(() => {
    options.forEach((option) => {
      const animation = optionAnimations.current[option.key];
      if (animation) {
        const isFocused = focusedKey === option.key;
        Animated.timing(animation, {
          toValue: isFocused ? 1.05 : 1,
          duration: 300,
          useNativeDriver: true,
        }).start();
      }
    });
  }, [focusedKey, options]);

  // 当 nextFocusUp 改变时，获取其节点句柄
  useEffect(() => {
    if (nextFocusUp) {
      const handle = findNodeHandle(nextFocusUp);
      setNextFocusUpHandle(handle);
    } else {
      setNextFocusUpHandle(null);
    }
  }, [nextFocusUp]);

  return (
    <TVFocusGuideView style={styles.container}>
      {options.map((option) => {
        const isFocused = focusedKey === option.key;
        const hasSelection = option.selectedValue && option.selectedValue !== '全部';
        
        return (
          <AnimatedPressable
            key={option.key}
            ref={(el) => {
              optionRefs.current[option.key] = el;
            }}
            style={[
              styles.option,
              isFocused && styles.optionFocused,
              {
                transform: [{ scale: optionAnimations.current[option.key] || 1 }],
              }
            ]}
            onFocus={() => onFocus(option.key)}
            onBlur={onBlur}
            onPress={() => onSelect(option.key)}
            nextFocusUp={nextFocusUpHandle}
          >
            <>
              <Text style={[
                styles.optionText,
                isFocused && styles.optionTextFocused
              ]}>
                {hasSelection ? option.selectedValue : option.label}
              </Text>
              <ChevronDown 
                size={14} 
                color={isFocused ? '#0a0a0a' : '#ffffff'}
                style={styles.icon}
              />
            </>
          </AnimatedPressable>
        );
      })}
    </TVFocusGuideView>
  );
});

TransparentFilterSelector.displayName = 'TransparentFilterSelector';

export default TransparentFilterSelector;

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    backgroundColor: 'transparent',
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
  icon: {
    marginLeft: 4,
  },
});
