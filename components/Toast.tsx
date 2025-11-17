import { StyleSheet, View, Text, Animated } from 'react-native';
import { useState, useEffect, useRef } from 'react';
import ToastService from '../services/toastService';

export default function Toast() {
  const [message, setMessage] = useState('');
  const [visible, setVisible] = useState(false);
  const [isError, setIsError] = useState(false);
  const opacity = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(20)).current;

  useEffect(() => {
    ToastService.setCallback((msg: string, error: boolean = false) => {
      setMessage(msg);
      setIsError(error);
      setVisible(true);

      // 显示动画
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 1,
          duration: 300,
          useNativeDriver: true,
        }),
        Animated.spring(translateY, {
          toValue: 0,
          useNativeDriver: true,
          friction: 8,
          tension: 100,
        }),
      ]).start();

      // 2秒后自动隐藏
      setTimeout(() => {
        Animated.parallel([
          Animated.timing(opacity, {
            toValue: 0,
            duration: 300,
            useNativeDriver: true,
          }),
          Animated.timing(translateY, {
            toValue: 20,
            duration: 300,
            useNativeDriver: true,
          }),
        ]).start(() => {
          setVisible(false);
        });
      }, 2000);
    });
  }, [opacity, translateY]);

  if (!visible) return null;

  return (
    <Animated.View
      style={[
        styles.toast,
        isError ? styles.toastError : styles.toastSuccess,
        {
          opacity: opacity,
          transform: [{ translateY: translateY }],
        },
      ]}
    >
      <Text style={styles.toastText}>{message}</Text>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  toast: {
    position: 'absolute',
    bottom: 30,
    alignSelf: 'center',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 20,
    maxWidth: 300,
    zIndex: 9999,
  },
  toastSuccess: {
    backgroundColor: 'rgba(39, 174, 96, 0.95)',
  },
  toastError: {
    backgroundColor: 'rgba(231, 76, 60, 0.95)',
  },
  toastText: {
    fontSize: 13,
    color: '#ffffff',
    fontWeight: '500',
    textAlign: 'center',
  },
});
