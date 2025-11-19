import { StyleSheet, View, Text, ScrollView, BackHandler, Animated } from 'react-native';
import { useLocalSearchParams, router } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';
import { useEffect, useRef } from 'react';

export default function DetailScreen() {
  const params = useLocalSearchParams();
  const { title, doubanId, from } = params;
  
  // 动画值
  const translateX = useRef(new Animated.Value(50)).current;
  const opacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    console.log('详情页参数:', { title, doubanId, from });
  }, [title, doubanId, from]);

  // 进入动画
  useEffect(() => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 200,
        useNativeDriver: true,
      }),
      Animated.timing(translateX, {
        toValue: 0,
        duration: 200,
        useNativeDriver: true,
      }),
    ]).start();
  }, []);

  // 监听返回键
  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      // 退出动画
      Animated.parallel([
        Animated.timing(opacity, {
          toValue: 0,
          duration: 100,
          useNativeDriver: true,
        }),
        Animated.timing(translateX, {
          toValue: 50,
          duration: 100,
          useNativeDriver: true,
        }),
      ]).start(() => {
        router.back();
      });
      return true;
    });

    return () => backHandler.remove();
  }, []);

  return (
    <LinearGradient
      colors={['#0a0a0a', '#1a1a1a', '#0f0f0f', '#050505']}
      locations={[0, 0.4, 0.7, 1]}
      style={styles.container}
    >
      <Animated.View
        style={[
          styles.animatedContainer,
          {
            opacity,
            transform: [{ translateX }],
          },
        ]}
      >
      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        <View style={styles.content}>
          <View style={styles.infoSection}>
            <Text style={styles.title}>{title}</Text>
            <View style={styles.metaRow}>
              <View style={styles.badge}>
                <Text style={styles.badgeText}>豆瓣</Text>
              </View>
              <Text style={styles.doubanId}>ID: {doubanId}</Text>
              {from && (
                <View style={styles.fromBadge}>
                  <Text style={styles.fromBadgeText}>来源: {from}</Text>
                </View>
              )}
            </View>
          </View>
          
          <View style={styles.placeholder}>
            <Text style={styles.placeholderText}>详情内容开发中...</Text>
            <Text style={styles.placeholderSubtext}>
              未来将显示：剧情简介、演员信息、评分详情等
            </Text>
          </View>
        </View>
      </ScrollView>
      </Animated.View>
    </LinearGradient>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  animatedContainer: {
    flex: 1,
  },
  scrollView: {
    flex: 1,
  },
  content: {
    paddingHorizontal: 40,
    paddingTop: 60,
    paddingBottom: 40,
  },
  infoSection: {
    marginBottom: 30,
  },
  title: {
    fontSize: 36,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 16,
    lineHeight: 44,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  badge: {
    backgroundColor: '#E91E63',
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 6,
  },
  badgeText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '700',
  },
  fromBadge: {
    backgroundColor: 'rgba(255, 255, 255, 0.15)',
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 6,
  },
  fromBadgeText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '600',
  },
  doubanId: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.6)',
    fontWeight: '500',
  },
  placeholder: {
    marginTop: 20,
    padding: 40,
    backgroundColor: 'rgba(255, 255, 255, 0.05)',
    borderRadius: 12,
    alignItems: 'center',
  },
  placeholderText: {
    fontSize: 18,
    color: 'rgba(255, 255, 255, 0.5)',
    fontWeight: '600',
    marginBottom: 12,
  },
  placeholderSubtext: {
    fontSize: 14,
    color: 'rgba(255, 255, 255, 0.3)',
    textAlign: 'center',
    lineHeight: 20,
  },
});
