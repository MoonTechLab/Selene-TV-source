import { StyleSheet, Text, Pressable, Animated, TVFocusGuideView, findNodeHandle } from 'react-native';
import { useRef, useEffect, useState } from 'react';

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
}

export default function FilterSelector({ 
  options, 
  selectedKey, 
  focusedKey,
  onFocus, 
  onBlur,
  onSelect 
}: FilterSelectorProps) {
  // 为每个选项创建 ref
  const optionRefs = useRef<Record<string, any>>({});
  const [preferredFocusTarget, setPreferredFocusTarget] = useState<number | null>(null);
  
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
      Animated.timing(optionAnimations[option.key], {
        toValue: isFocused ? 1.05 : 1,
        duration: 300,
        useNativeDriver: true,
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

  return (
    <TVFocusGuideView 
      style={styles.container}
      destinations={preferredFocusTarget ? [preferredFocusTarget] : undefined}
    >
      {options.map((option) => {
        const isSelected = selectedKey === option.key;
        const isFocused = focusedKey === option.key;
        
        return (
          <Animated.View
            key={option.key}
            style={{
              transform: [{ scale: optionAnimations[option.key] }],
            }}
          >
            <Pressable
              ref={(el) => {
                optionRefs.current[option.key] = el;
              }}
              style={[
                styles.option,
                isSelected && !isFocused && styles.optionSelected,
                isFocused && styles.optionFocused,
              ]}
              onFocus={() => onFocus(option.key)}
              onBlur={onBlur}
              onPress={() => onSelect(option.key)}
            >
              <Text style={[
                styles.optionText,
                isFocused && styles.optionTextFocused
              ]}>
                {option.label}
              </Text>
            </Pressable>
          </Animated.View>
        );
      })}
    </TVFocusGuideView>
  );
}

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
