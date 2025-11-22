import { StyleSheet, View, Text, Modal, FlatList, TextInput, Pressable, Animated, ActivityIndicator } from 'react-native';
import { useState, useEffect, useRef } from 'react';
import AppDataService from '../../services/appDataService';
import ToastService from '../../services/toastService';
import userDataCacheService from '../../services/userDataCacheService';
import doubanCacheService from '../../services/doubanCacheService';
import searchCacheService from '../../services/searchCacheService';

const DOUBAN_DATA_SOURCES = [
  '直连',
  'Cors Proxy By Zwei',
  '豆瓣 CDN By CMLiussss（腾讯云）',
  '豆瓣 CDN By CMLiussss（阿里云）',
];

const DOUBAN_IMAGE_SOURCES = [
  '直连',
  '豆瓣官方精品 CDN',
  '豆瓣 CDN By CMLiussss（腾讯云）',
  '豆瓣 CDN By CMLiussss（阿里云）',
];

export default function SettingsTab() {
  const [hasLogin, setHasLogin] = useState(true);
  const [doubanDataSource, setDoubanDataSource] = useState('直连');
  const [doubanImageSource, setDoubanImageSource] = useState('直连');
  const [m3u8ProxyUrl, setM3u8ProxyUrl] = useState('');
  const [tmdbApiKey, setTmdbApiKey] = useState('');
  
  // 用户信息
  const [username, setUsername] = useState('');
  const [serverUrl, setServerUrl] = useState('');
  const [userRole, setUserRole] = useState('user');
  
  const [showDataSourceModal, setShowDataSourceModal] = useState(false);
  const [showImageSourceModal, setShowImageSourceModal] = useState(false);
  const [showProxyModal, setShowProxyModal] = useState(false);
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [showTmdbModal, setShowTmdbModal] = useState(false);
  const [tempProxyUrl, setTempProxyUrl] = useState('');
  const [tempTmdbApiKey, setTempTmdbApiKey] = useState('');
  
  // 登录表单数据
  const [loginServerUrl, setLoginServerUrl] = useState('');
  const [loginUsername, setLoginUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  
  const [focusedItem, setFocusedItem] = useState<string | null>(null);
  const [focusedModalItem, setFocusedModalItem] = useState<string | null>(null);

  // Modal 动画状态
  const [dataSourceModalVisible, setDataSourceModalVisible] = useState(false);
  const [imageSourceModalVisible, setImageSourceModalVisible] = useState(false);
  const [proxyModalVisible, setProxyModalVisible] = useState(false);
  const [loginModalVisible, setLoginModalVisible] = useState(false);
  const [tmdbModalVisible, setTmdbModalVisible] = useState(false);

  // 动画值
  const dataSourceScale = useRef(new Animated.Value(0.8)).current;
  const dataSourceOpacity = useRef(new Animated.Value(0)).current;
  const dataSourceOverlayOpacity = useRef(new Animated.Value(0)).current;

  const imageSourceScale = useRef(new Animated.Value(0.8)).current;
  const imageSourceOpacity = useRef(new Animated.Value(0)).current;
  const imageSourceOverlayOpacity = useRef(new Animated.Value(0)).current;

  const proxyScale = useRef(new Animated.Value(0.8)).current;
  const proxyOpacity = useRef(new Animated.Value(0)).current;
  const proxyOverlayOpacity = useRef(new Animated.Value(0)).current;

  const loginScale = useRef(new Animated.Value(0.8)).current;
  const loginOpacity = useRef(new Animated.Value(0)).current;
  const loginOverlayOpacity = useRef(new Animated.Value(0)).current;

  const tmdbScale = useRef(new Animated.Value(0.8)).current;
  const tmdbOpacity = useRef(new Animated.Value(0)).current;
  const tmdbOverlayOpacity = useRef(new Animated.Value(0)).current;

  // 设置项聚焦动画
  const settingItemAnimations = useRef({
    login: new Animated.Value(1),
    dataSource: new Animated.Value(1),
    imageSource: new Animated.Value(1),
    proxy: new Animated.Value(1),
    tmdb: new Animated.Value(1),
    clearDoubanCache: new Animated.Value(1),
    clearSearchCache: new Animated.Value(1),
    clearUserDataCache: new Animated.Value(1),
    logout: new Animated.Value(1),
  }).current;

  // Modal 选项聚焦动画
  const modalItemAnimations = useRef<Record<string, Animated.Value>>({}).current;

  // 初始化 modal 选项动画值
  const getModalItemAnimation = (key: string) => {
    if (!modalItemAnimations[key]) {
      modalItemAnimations[key] = new Animated.Value(1);
    }
    return modalItemAnimations[key];
  };

  useEffect(() => {
    loadSettings();
  }, []);

  // 处理设置项聚焦动画
  useEffect(() => {
    ['login', 'dataSource', 'imageSource', 'proxy', 'tmdb', 'clearDoubanCache', 'clearSearchCache', 'clearUserDataCache', 'logout'].forEach((item) => {
      const isFocused = focusedItem === item;
      Animated.spring(settingItemAnimations[item as keyof typeof settingItemAnimations], {
        toValue: isFocused ? 1.02 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedItem, settingItemAnimations]);

  // 处理 modal 选项聚焦动画
  useEffect(() => {
    Object.keys(modalItemAnimations).forEach((key) => {
      const isFocused = focusedModalItem === key;
      Animated.spring(modalItemAnimations[key], {
        toValue: isFocused ? 1.02 : 1,
        useNativeDriver: true,
        friction: 8,
        tension: 100,
      }).start();
    });
  }, [focusedModalItem, modalItemAnimations]);

  const loadSettings = async () => {
    const isLoggedIn = await AppDataService.isLoggedIn();
    const dataSource = await AppDataService.getDoubanDataSourceDisplayName();
    const imageSource = await AppDataService.getDoubanImageSourceDisplayName();
    const proxyUrl = await AppDataService.getM3u8ProxyUrl();
    const apiKey = await AppDataService.getTmdbApiKey();
    
    setHasLogin(isLoggedIn);
    setDoubanDataSource(dataSource);
    setDoubanImageSource(imageSource);
    setM3u8ProxyUrl(proxyUrl);
    setTmdbApiKey(apiKey);
    
    // 如果已登录，加载用户信息
    if (isLoggedIn) {
      const userData = await AppDataService.getAllUserData();
      setUsername(userData.username || '');
      setServerUrl(userData.serverUrl || '');
      const role = AppDataService.parseRoleFromCookies(userData.cookies);
      setUserRole(role);
    }
  };

  const handleLogin = async () => {
    // 从存储中加载 serverUrl 和 username 用于回显
    const userData = await AppDataService.getAllUserData();
    if (userData.serverUrl) {
      setLoginServerUrl(userData.serverUrl);
    }
    if (userData.username) {
      setLoginUsername(userData.username);
    }
    // password 不回显，保持为空
    setLoginPassword('');
    
    setShowLoginModal(true);
  };

  // 处理 URL：移除末尾斜杠，添加 http:// 前缀（如果需要）
  const processUrl = (url: string): string => {
    let processedUrl = url.trim();
    
    // 移除末尾的斜杠
    if (processedUrl.endsWith('/')) {
      processedUrl = processedUrl.slice(0, -1);
    }
    
    // 如果没有协议前缀，添加 http://
    if (!processedUrl.startsWith('http://') && !processedUrl.startsWith('https://')) {
      processedUrl = 'http://' + processedUrl;
    }
    
    return processedUrl;
  };

  // 从响应头中解析 cookies
  const parseCookies = (response: Response): string => {
    const setCookieHeader = response.headers.get('set-cookie');
    if (!setCookieHeader) return '';
    
    // 解析多个 cookie
    const cookies = setCookieHeader.split(',').map(cookie => {
      const parts = cookie.split(';');
      return parts[0].trim();
    }).join('; ');
    
    return cookies;
  };

  // 表单验证
  const isFormValid = (): boolean => {
    return !!(
      loginServerUrl.trim() &&
      loginUsername.trim() &&
      loginPassword.trim()
    );
  };



  const handleLoginSubmit = async () => {
    // 表单验证
    if (!isFormValid()) {
      ToastService.error('请填写完整的登录信息');
      return;
    }

    setIsLoading(true);

    try {
      // 处理 URL
      const baseUrl = processUrl(loginServerUrl);
      const loginUrl = `${baseUrl}/api/login`;

      // 发送登录请求
      const response = await fetch(loginUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username: loginUsername,
          password: loginPassword,
        }),
      });

      setIsLoading(false);

      // 根据状态码处理不同情况
      switch (response.status) {
        case 200:
          // 解析并保存 cookies
          const cookies = parseCookies(response);

          // 保存用户数据
          await AppDataService.saveUserData({
            serverUrl: baseUrl,
            username: loginUsername,
            password: loginPassword,
            cookies: cookies,
          });

          // 关闭弹窗并更新登录状态
          setShowLoginModal(false);
          setHasLogin(true);
          
          // 更新用户信息
          setUsername(loginUsername);
          setServerUrl(baseUrl);
          const role = AppDataService.parseRoleFromCookies(cookies);
          setUserRole(role);
          
          // 清空表单
          setLoginServerUrl('');
          setLoginUsername('');
          setLoginPassword('');

          ToastService.success('登录成功！');
          break;

        case 401:
          ToastService.error('用户名或密码错误');
          break;

        case 500:
          ToastService.error('服务器错误');
          break;

        default:
          ToastService.error('网络异常');
      }
    } catch (error) {
      setIsLoading(false);
      console.error('登录错误:', error);
      ToastService.error('网络异常');
    }
  };

  // 数据源 Modal 动画
  useEffect(() => {
    if (showDataSourceModal) {
      setDataSourceModalVisible(true);
      Animated.parallel([
        Animated.spring(dataSourceScale, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(dataSourceOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(dataSourceOverlayOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
      ]).start();
    } else if (dataSourceModalVisible) {
      Animated.parallel([
        Animated.spring(dataSourceScale, { toValue: 0.8, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(dataSourceOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(dataSourceOverlayOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
      ]).start(() => setDataSourceModalVisible(false));
    }
  }, [showDataSourceModal, dataSourceModalVisible, dataSourceScale, dataSourceOpacity, dataSourceOverlayOpacity]);

  // 图片源 Modal 动画
  useEffect(() => {
    if (showImageSourceModal) {
      setImageSourceModalVisible(true);
      Animated.parallel([
        Animated.spring(imageSourceScale, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(imageSourceOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(imageSourceOverlayOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
      ]).start();
    } else if (imageSourceModalVisible) {
      Animated.parallel([
        Animated.spring(imageSourceScale, { toValue: 0.8, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(imageSourceOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(imageSourceOverlayOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
      ]).start(() => setImageSourceModalVisible(false));
    }
  }, [showImageSourceModal, imageSourceModalVisible, imageSourceScale, imageSourceOpacity, imageSourceOverlayOpacity]);

  // 代理 Modal 动画
  useEffect(() => {
    if (showProxyModal) {
      setProxyModalVisible(true);
      Animated.parallel([
        Animated.spring(proxyScale, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(proxyOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(proxyOverlayOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
      ]).start();
    } else if (proxyModalVisible) {
      Animated.parallel([
        Animated.spring(proxyScale, { toValue: 0.8, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(proxyOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(proxyOverlayOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
      ]).start(() => setProxyModalVisible(false));
    }
  }, [showProxyModal, proxyModalVisible, proxyScale, proxyOpacity, proxyOverlayOpacity]);

  // 登录 Modal 动画
  useEffect(() => {
    if (showLoginModal) {
      setLoginModalVisible(true);
      Animated.parallel([
        Animated.spring(loginScale, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(loginOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(loginOverlayOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
      ]).start();
    } else if (loginModalVisible) {
      Animated.parallel([
        Animated.spring(loginScale, { toValue: 0.8, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(loginOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(loginOverlayOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
      ]).start(() => setLoginModalVisible(false));
    }
  }, [showLoginModal, loginModalVisible, loginScale, loginOpacity, loginOverlayOpacity]);

  // TMDB Modal 动画
  useEffect(() => {
    if (showTmdbModal) {
      setTmdbModalVisible(true);
      Animated.parallel([
        Animated.spring(tmdbScale, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(tmdbOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
        Animated.spring(tmdbOverlayOpacity, { toValue: 1, useNativeDriver: true, friction: 10, tension: 150 }),
      ]).start();
    } else if (tmdbModalVisible) {
      Animated.parallel([
        Animated.spring(tmdbScale, { toValue: 0.8, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(tmdbOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
        Animated.spring(tmdbOverlayOpacity, { toValue: 0, useNativeDriver: true, friction: 12, tension: 200 }),
      ]).start(() => setTmdbModalVisible(false));
    }
  }, [showTmdbModal, tmdbModalVisible, tmdbScale, tmdbOpacity, tmdbOverlayOpacity]);

  const handleDataSourceSelect = async (source: string) => {
    await AppDataService.saveDoubanDataSource(source);
    setDoubanDataSource(source);
    setShowDataSourceModal(false);
  };

  const handleImageSourceSelect = async (source: string) => {
    await AppDataService.saveDoubanImageSource(source);
    setDoubanImageSource(source);
    setShowImageSourceModal(false);
  };

  const handleProxyUrlSave = async () => {
    await AppDataService.saveM3u8ProxyUrl(tempProxyUrl);
    setM3u8ProxyUrl(tempProxyUrl);
    setShowProxyModal(false);
  };

  const openProxyModal = () => {
    setTempProxyUrl(m3u8ProxyUrl);
    setShowProxyModal(true);
  };

  const handleTmdbApiKeySave = async () => {
    await AppDataService.saveTmdbApiKey(tempTmdbApiKey);
    setTmdbApiKey(tempTmdbApiKey);
    setShowTmdbModal(false);
  };

  const openTmdbModal = () => {
    setTempTmdbApiKey(tmdbApiKey);
    setShowTmdbModal(true);
  };

  const handleLogout = async () => {
    await AppDataService.clearPasswordAndCookies();
    userDataCacheService.clearAllCache();
    setHasLogin(false);
    setUsername('');
    setServerUrl('');
    setUserRole('user');
    ToastService.success('已登出');
  };

  const handleClearDoubanCache = async () => {
    try {
      await doubanCacheService.clearAllCache();
      ToastService.success('豆瓣缓存已清空');
    } catch (error) {
      console.error('清空豆瓣缓存失败:', error);
      ToastService.error('清空豆瓣缓存失败');
    }
  };

  const handleClearSearchCache = async () => {
    try {
      searchCacheService.clearCache();
      ToastService.success('搜索缓存已清空');
    } catch (error) {
      console.error('清空搜索缓存失败:', error);
      ToastService.error('清空搜索缓存失败');
    }
  };

  const handleClearUserDataCache = async () => {
    try {
      userDataCacheService.clearAllCache();
      ToastService.success('用户数据缓存已清空');
    } catch (error) {
      console.error('清空用户数据缓存失败:', error);
      ToastService.error('清空用户数据缓存失败');
    }
  };

  // 从 serverUrl 中提取域名
  const extractDomain = (url: string): string => {
    try {
      const urlObj = new URL(url);
      return urlObj.hostname;
    } catch {
      return url;
    }
  };

  // 获取角色显示文本
  const getRoleText = (role: string): string => {
    switch (role) {
      case 'owner':
        return '站长';
      case 'admin':
        return '管理';
      case 'user':
        return '用户';
      default:
        return '用户';
    }
  };

  // 获取角色颜色
  const getRoleColor = (role: string): string => {
    switch (role) {
      case 'owner':
        return '#9b59b6'; // 紫色
      case 'admin':
        return '#f39c12'; // 黄色
      case 'user':
        return '#27ae60'; // 绿色
      default:
        return '#27ae60';
    }
  };

  return (
    <>
      <View style={styles.container}>
        <View style={styles.content}>
          {/* 用户信息 - 仅在已登录时显示 */}
          {hasLogin && (
            <View style={styles.userInfoContainer}>
              <Text style={styles.userInfoLabel}>当前用户</Text>
              <View style={styles.usernameRow}>
                <Text style={styles.username}>{username}</Text>
                <View style={[styles.roleBadge, { backgroundColor: getRoleColor(userRole) }]}>
                  <Text style={styles.roleText}>{getRoleText(userRole)}</Text>
                </View>
              </View>
            </View>
          )}

          <View style={styles.settingsGroup}>
          {/* 登录实例 - 仅在已登录时显示 */}
          {hasLogin && (
            <View style={styles.serverInfoItem}>
              <Text style={styles.serverInfoLabel}>登录实例</Text>
              <Text style={styles.serverInfoValue}>{extractDomain(serverUrl)}</Text>
            </View>
          )}

          {/* 登录选项 - 仅在未登录时显示 */}
          {!hasLogin && (
            <Animated.View style={{ transform: [{ scale: settingItemAnimations.login }] }}>
              <Pressable
                style={[
                  styles.settingItem,
                  focusedItem === 'login' && styles.settingItemFocused
                ]}
                onPress={handleLogin}
                onFocus={() => setFocusedItem('login')}
                onBlur={() => setFocusedItem(null)}
              >
                <Text style={[
                  styles.settingLabel,
                  focusedItem === 'login' && styles.settingLabelFocused
                ]}>登录</Text>
                <Text style={[
                  styles.settingValue,
                  focusedItem === 'login' && styles.settingValueFocused
                ]}>点击登录</Text>
              </Pressable>
            </Animated.View>
          )}

          {/* 豆瓣数据源 */}
          <Animated.View style={{ transform: [{ scale: settingItemAnimations.dataSource }] }}>
            <Pressable
              style={[
                styles.settingItem,
                focusedItem === 'dataSource' && styles.settingItemFocused
              ]}
              onPress={() => setShowDataSourceModal(true)}
              onFocus={() => setFocusedItem('dataSource')}
              onBlur={() => setFocusedItem(null)}
            >
              <Text style={[
                styles.settingLabel,
                focusedItem === 'dataSource' && styles.settingLabelFocused
              ]}>豆瓣数据源</Text>
              <Text style={[
                styles.settingValue,
                focusedItem === 'dataSource' && styles.settingValueFocused
              ]}>{doubanDataSource}</Text>
            </Pressable>
          </Animated.View>

          {/* 豆瓣图片源 */}
          <Animated.View style={{ transform: [{ scale: settingItemAnimations.imageSource }] }}>
            <Pressable
              style={[
                styles.settingItem,
                focusedItem === 'imageSource' && styles.settingItemFocused
              ]}
              onPress={() => setShowImageSourceModal(true)}
              onFocus={() => setFocusedItem('imageSource')}
              onBlur={() => setFocusedItem(null)}
            >
              <Text style={[
                styles.settingLabel,
                focusedItem === 'imageSource' && styles.settingLabelFocused
              ]}>豆瓣图片源</Text>
              <Text style={[
                styles.settingValue,
                focusedItem === 'imageSource' && styles.settingValueFocused
              ]}>{doubanImageSource}</Text>
            </Pressable>
          </Animated.View>

          {/* TMDB API Key */}
          <Animated.View style={{ transform: [{ scale: settingItemAnimations.tmdb }] }}>
            <Pressable
              style={[
                styles.settingItem,
                focusedItem === 'tmdb' && styles.settingItemFocused
              ]}
              onPress={openTmdbModal}
              onFocus={() => setFocusedItem('tmdb')}
              onBlur={() => setFocusedItem(null)}
            >
              <Text style={[
                styles.settingLabel,
                focusedItem === 'tmdb' && styles.settingLabelFocused
              ]}>TMDB API Key</Text>
              <Text style={[
                styles.settingValue,
                focusedItem === 'tmdb' && styles.settingValueFocused
              ]} numberOfLines={1}>
                {tmdbApiKey || '未设置'}
              </Text>
            </Pressable>
          </Animated.View>

          {/* M3U8 代理 */}
          <Animated.View style={{ transform: [{ scale: settingItemAnimations.proxy }] }}>
            <Pressable
              style={[
                styles.settingItem,
                focusedItem === 'proxy' && styles.settingItemFocused
              ]}
              onPress={openProxyModal}
              onFocus={() => setFocusedItem('proxy')}
              onBlur={() => setFocusedItem(null)}
            >
              <Text style={[
                styles.settingLabel,
                focusedItem === 'proxy' && styles.settingLabelFocused
              ]}>M3U8 代理</Text>
              <Text style={[
                styles.settingValue,
                focusedItem === 'proxy' && styles.settingValueFocused
              ]} numberOfLines={1}>
                {m3u8ProxyUrl || '未设置'}
              </Text>
            </Pressable>
          </Animated.View>

          {/* 清空缓存选项 - 一行三个按钮 */}
          <View style={styles.cacheButtonsRow}>
            <Animated.View style={{ flex: 1, transform: [{ scale: settingItemAnimations.clearDoubanCache }] }}>
              <Pressable
                style={[
                  styles.cacheButton,
                  focusedItem === 'clearDoubanCache' && styles.cacheButtonFocused
                ]}
                onPress={handleClearDoubanCache}
                onFocus={() => setFocusedItem('clearDoubanCache')}
                onBlur={() => setFocusedItem(null)}
              >
                <Text style={[
                  styles.cacheButtonText,
                  focusedItem === 'clearDoubanCache' && styles.cacheButtonTextFocused
                ]}>清空豆瓣缓存</Text>
              </Pressable>
            </Animated.View>
            <Animated.View style={{ flex: 1, transform: [{ scale: settingItemAnimations.clearSearchCache }] }}>
              <Pressable
                style={[
                  styles.cacheButton,
                  focusedItem === 'clearSearchCache' && styles.cacheButtonFocused
                ]}
                onPress={handleClearSearchCache}
                onFocus={() => setFocusedItem('clearSearchCache')}
                onBlur={() => setFocusedItem(null)}
              >
                <Text style={[
                  styles.cacheButtonText,
                  focusedItem === 'clearSearchCache' && styles.cacheButtonTextFocused
                ]}>清空搜索缓存</Text>
              </Pressable>
            </Animated.View>
            <Animated.View style={{ flex: 1, transform: [{ scale: settingItemAnimations.clearUserDataCache }] }}>
              <Pressable
                style={[
                  styles.cacheButton,
                  focusedItem === 'clearUserDataCache' && styles.cacheButtonFocused
                ]}
                onPress={handleClearUserDataCache}
                onFocus={() => setFocusedItem('clearUserDataCache')}
                onBlur={() => setFocusedItem(null)}
              >
                <Text style={[
                  styles.cacheButtonText,
                  focusedItem === 'clearUserDataCache' && styles.cacheButtonTextFocused
                ]}>清空用户数据缓存</Text>
              </Pressable>
            </Animated.View>
          </View>

          {/* 登出选项 - 仅在已登录时显示 */}
          {hasLogin && (
            <Animated.View style={{ transform: [{ scale: settingItemAnimations.logout }] }}>
              <Pressable
                style={[
                  styles.settingItem,
                  styles.logoutItem,
                  focusedItem === 'logout' && styles.logoutItemFocused
                ]}
                onPress={handleLogout}
                onFocus={() => setFocusedItem('logout')}
                onBlur={() => setFocusedItem(null)}
              >
                <Text style={[
                  styles.logoutText,
                  focusedItem === 'logout' && styles.logoutTextFocused
                ]}>退出登录</Text>
              </Pressable>
            </Animated.View>
          )}
        </View>
      </View>

      {/* 数据源选择弹窗 */}
      <Modal
        visible={dataSourceModalVisible}
        transparent={true}
        animationType="none"
        onRequestClose={() => setShowDataSourceModal(false)}
      >
        <Animated.View style={[styles.modalOverlay, { opacity: dataSourceOverlayOpacity }]}>
          <Animated.View style={[styles.modalContent, { transform: [{ scale: dataSourceScale }], opacity: dataSourceOpacity }]}>
            <Text style={styles.modalTitle}>选择豆瓣数据源</Text>
            <FlatList
              data={DOUBAN_DATA_SOURCES}
              keyExtractor={(item) => item}
              contentContainerStyle={styles.modalListContent}
              renderItem={({ item }) => {
                const itemKey = `dataSource-${item}`;
                const animation = getModalItemAnimation(itemKey);
                return (
                  <Animated.View style={{ transform: [{ scale: animation }] }}>
                    <Pressable
                      style={[
                        styles.modalItem,
                        focusedModalItem === itemKey && styles.modalItemFocused,
                      ]}
                      onPress={() => handleDataSourceSelect(item)}
                      onFocus={() => setFocusedModalItem(itemKey)}
                      onBlur={() => setFocusedModalItem(null)}
                    >
                      <Text style={[
                        styles.modalItemText,
                        focusedModalItem === itemKey && styles.modalItemTextFocused,
                      ]}>
                        {item}
                      </Text>
                    </Pressable>
                  </Animated.View>
                );
              }}
            />
            <Animated.View style={{ transform: [{ scale: getModalItemAnimation('dataSource-close') }] }}>
              <Pressable
                style={[
                  styles.modalCloseButton,
                  focusedModalItem === 'dataSource-close' && styles.modalButtonFocused,
                ]}
                onPress={() => setShowDataSourceModal(false)}
                onFocus={() => setFocusedModalItem('dataSource-close')}
                onBlur={() => setFocusedModalItem(null)}
              >
                <Text style={[
                  styles.modalCloseText,
                  focusedModalItem === 'dataSource-close' && styles.modalButtonTextFocused,
                ]}>取消</Text>
              </Pressable>
            </Animated.View>
          </Animated.View>
        </Animated.View>
      </Modal>

      {/* 图片源选择弹窗 */}
      <Modal
        visible={imageSourceModalVisible}
        transparent={true}
        animationType="none"
        onRequestClose={() => setShowImageSourceModal(false)}
      >
        <Animated.View style={[styles.modalOverlay, { opacity: imageSourceOverlayOpacity }]}>
          <Animated.View style={[styles.modalContent, { transform: [{ scale: imageSourceScale }], opacity: imageSourceOpacity }]}>
            <Text style={styles.modalTitle}>选择豆瓣图片源</Text>
            <FlatList
              data={DOUBAN_IMAGE_SOURCES}
              keyExtractor={(item) => item}
              contentContainerStyle={styles.modalListContent}
              renderItem={({ item }) => {
                const itemKey = `imageSource-${item}`;
                const animation = getModalItemAnimation(itemKey);
                return (
                  <Animated.View style={{ transform: [{ scale: animation }] }}>
                    <Pressable
                      style={[
                        styles.modalItem,
                        focusedModalItem === itemKey && styles.modalItemFocused,
                      ]}
                      onPress={() => handleImageSourceSelect(item)}
                      onFocus={() => setFocusedModalItem(itemKey)}
                      onBlur={() => setFocusedModalItem(null)}
                    >
                      <Text style={[
                        styles.modalItemText,
                        focusedModalItem === itemKey && styles.modalItemTextFocused,
                      ]}>
                        {item}
                      </Text>
                    </Pressable>
                  </Animated.View>
                );
              }}
            />
            <Animated.View style={{ transform: [{ scale: getModalItemAnimation('imageSource-close') }] }}>
              <Pressable
                style={[
                  styles.modalCloseButton,
                  focusedModalItem === 'imageSource-close' && styles.modalButtonFocused,
                ]}
                onPress={() => setShowImageSourceModal(false)}
                onFocus={() => setFocusedModalItem('imageSource-close')}
                onBlur={() => setFocusedModalItem(null)}
              >
                <Text style={[
                  styles.modalCloseText,
                  focusedModalItem === 'imageSource-close' && styles.modalButtonTextFocused,
                ]}>取消</Text>
              </Pressable>
            </Animated.View>
          </Animated.View>
        </Animated.View>
      </Modal>

      {/* M3U8 代理输入弹窗 */}
      <Modal
        visible={proxyModalVisible}
        transparent={true}
        animationType="none"
        onRequestClose={() => setShowProxyModal(false)}
      >
        <Animated.View style={[styles.modalOverlay, { opacity: proxyOverlayOpacity }]}>
          <Animated.View style={[styles.modalContent, { transform: [{ scale: proxyScale }], opacity: proxyOpacity }]}>
            <Text style={styles.modalTitle}>设置 M3U8 代理</Text>
            <TextInput
              style={styles.input}
              value={tempProxyUrl}
              onChangeText={setTempProxyUrl}
              placeholder="输入代理地址"
              placeholderTextColor="#666"
              autoFocus={true}
            />
            <View style={styles.modalButtons}>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('proxy-cancel') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'proxy-cancel' && styles.modalButtonFocused,
                  ]}
                  onPress={() => setShowProxyModal(false)}
                  onFocus={() => setFocusedModalItem('proxy-cancel')}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'proxy-cancel' && styles.modalButtonTextFocused,
                  ]}>取消</Text>
                </Pressable>
              </Animated.View>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('proxy-confirm') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'proxy-confirm' && styles.modalButtonFocused,
                  ]}
                  onPress={handleProxyUrlSave}
                  onFocus={() => setFocusedModalItem('proxy-confirm')}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'proxy-confirm' && styles.modalButtonTextFocused,
                  ]}>确定</Text>
                </Pressable>
              </Animated.View>
            </View>
          </Animated.View>
        </Animated.View>
      </Modal>

      {/* TMDB API Key 输入弹窗 */}
      <Modal
        visible={tmdbModalVisible}
        transparent={true}
        animationType="none"
        onRequestClose={() => setShowTmdbModal(false)}
      >
        <Animated.View style={[styles.modalOverlay, { opacity: tmdbOverlayOpacity }]}>
          <Animated.View style={[styles.modalContent, { transform: [{ scale: tmdbScale }], opacity: tmdbOpacity }]}>
            <Text style={styles.modalTitle}>设置 TMDB API Key</Text>
            <TextInput
              style={styles.input}
              value={tempTmdbApiKey}
              onChangeText={setTempTmdbApiKey}
              placeholder="输入 TMDB API Key"
              placeholderTextColor="#666"
              autoFocus={true}
            />
            <View style={styles.modalButtons}>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('tmdb-cancel') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'tmdb-cancel' && styles.modalButtonFocused,
                  ]}
                  onPress={() => setShowTmdbModal(false)}
                  onFocus={() => setFocusedModalItem('tmdb-cancel')}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'tmdb-cancel' && styles.modalButtonTextFocused,
                  ]}>取消</Text>
                </Pressable>
              </Animated.View>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('tmdb-confirm') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'tmdb-confirm' && styles.modalButtonFocused,
                  ]}
                  onPress={handleTmdbApiKeySave}
                  onFocus={() => setFocusedModalItem('tmdb-confirm')}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'tmdb-confirm' && styles.modalButtonTextFocused,
                  ]}>确定</Text>
                </Pressable>
              </Animated.View>
            </View>
          </Animated.View>
        </Animated.View>
      </Modal>

      {/* 登录弹窗 */}
      <Modal
        visible={loginModalVisible}
        transparent={true}
        animationType="none"
        onRequestClose={() => !isLoading && setShowLoginModal(false)}
      >
        <Animated.View style={[styles.modalOverlay, { opacity: loginOverlayOpacity }]}>
          <Animated.View style={[styles.modalContent, { transform: [{ scale: loginScale }], opacity: loginOpacity }]}>
            <Text style={styles.modalTitle}>登录</Text>
            <TextInput
              style={styles.input}
              value={loginServerUrl}
              onChangeText={setLoginServerUrl}
              placeholder="服务器地址"
              placeholderTextColor="#666"
              autoFocus={true}
              editable={!isLoading}
            />
            <TextInput
              style={styles.input}
              value={loginUsername}
              onChangeText={setLoginUsername}
              placeholder="用户名"
              placeholderTextColor="#666"
              editable={!isLoading}
            />
            <TextInput
              style={styles.input}
              value={loginPassword}
              onChangeText={setLoginPassword}
              placeholder="密码"
              placeholderTextColor="#666"
              secureTextEntry={true}
              editable={!isLoading}
            />
            {isLoading && (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="small" color="#ffffff" />
                <Text style={styles.loadingText}>登录中...</Text>
              </View>
            )}
            <View style={styles.modalButtons}>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('login-cancel') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'login-cancel' && styles.modalButtonFocused,
                    isLoading && styles.modalButtonDisabled,
                  ]}
                  onPress={() => !isLoading && setShowLoginModal(false)}
                  onFocus={() => setFocusedModalItem('login-cancel')}
                  onBlur={() => setFocusedModalItem(null)}
                  disabled={isLoading}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'login-cancel' && styles.modalButtonTextFocused,
                    isLoading && styles.modalButtonTextDisabled,
                  ]}>取消</Text>
                </Pressable>
              </Animated.View>
              <Animated.View style={{ flex: 1, transform: [{ scale: getModalItemAnimation('login-confirm') }] }}>
                <Pressable
                  style={[
                    styles.modalButton,
                    focusedModalItem === 'login-confirm' && styles.modalButtonFocused,
                    isLoading && styles.modalButtonDisabled,
                  ]}
                  onPress={handleLoginSubmit}
                  onFocus={() => setFocusedModalItem('login-confirm')}
                  onBlur={() => setFocusedModalItem(null)}
                  disabled={isLoading}
                >
                  <Text style={[
                    styles.modalButtonText,
                    focusedModalItem === 'login-confirm' && styles.modalButtonTextFocused,
                    isLoading && styles.modalButtonTextDisabled,
                  ]}>登录</Text>
                </Pressable>
              </Animated.View>
            </View>
          </Animated.View>
        </Animated.View>
      </Modal>

      </View>
    </>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'transparent',
    paddingTop: 20,
    alignItems: 'center',
  },
  content: {
    alignItems: 'center',
    overflow: 'visible',
  },
  userInfoContainer: {
    alignItems: 'center',
    marginBottom: 18,
  },
  userInfoLabel: {
    fontSize: 11,
    color: '#888',
    marginBottom: 6,
  },
  usernameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  username: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
  },
  roleBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 8,
  },
  roleText: {
    fontSize: 9,
    fontWeight: '600',
    color: '#ffffff',
  },
  serverInfoItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 8,
    paddingHorizontal: 18,
    borderRadius: 40,
    width: 480,
  },
  serverInfoLabel: {
    fontSize: 13,
    fontWeight: '500',
    color: '#ffffff',
  },
  serverInfoValue: {
    fontSize: 11,
    color: '#888',
    maxWidth: 260,
  },
  settingsGroup: {
    gap: 13,
    alignItems: 'center',
    overflow: 'visible',
  },
  settingItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingVertical: 8,
    paddingHorizontal: 18,
    borderRadius: 40,
    width: 480,
    zIndex: 1,
  },
  settingItemFocused: {
    backgroundColor: '#ffffff',
  },
  settingLabel: {
    fontSize: 13,
    fontWeight: '500',
    color: '#ffffff',
  },
  settingLabelFocused: {
    color: '#0a0a0a',
  },
  settingValue: {
    fontSize: 11,
    color: '#888',
    maxWidth: 260,
  },
  settingValueFocused: {
    color: '#0a0a0a',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.85)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalContent: {
    backgroundColor: '#1a1a1a',
    borderRadius: 10,
    padding: 16,
    width: 300,
    maxHeight: 320,
  },
  modalTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 12,
    textAlign: 'center',
  },
  modalListContent: {
    paddingHorizontal: 3,
    paddingVertical: 2,
  },
  modalItem: {
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 40,
    marginBottom: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  modalItemFocused: {
    backgroundColor: '#ffffff',
  },
  modalItemText: {
    fontSize: 11,
    color: '#ffffff',
  },
  modalItemTextFocused: {
    color: '#0a0a0a',
  },
  modalCloseButton: {
    marginTop: 5,
    paddingVertical: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderRadius: 6,
    alignItems: 'center',
  },
  modalCloseText: {
    fontSize: 11,
    color: '#ffffff',
    fontWeight: '500',
  },
  input: {
    backgroundColor: '#252525',
    borderRadius: 6,
    paddingVertical: 8,
    paddingHorizontal: 12,
    fontSize: 11,
    color: '#ffffff',
    marginBottom: 12,
  },
  modalButtons: {
    flexDirection: 'row',
    gap: 6,
  },
  modalButton: {
    paddingVertical: 8,
    borderRadius: 6,
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  modalButtonFocused: {
    backgroundColor: '#ffffff',
  },
  modalButtonText: {
    fontSize: 11,
    color: '#ffffff',
    fontWeight: '500',
  },
  modalButtonTextFocused: {
    color: '#0a0a0a',
  },
  modalButtonDisabled: {
    opacity: 0.5,
  },
  modalButtonTextDisabled: {
    opacity: 0.5,
  },
  loadingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
    gap: 6,
  },
  loadingText: {
    fontSize: 11,
    color: '#ffffff',
  },
  logoutItem: {
    backgroundColor: 'rgba(231, 76, 60, 0.15)',
    justifyContent: 'center',
  },
  logoutItemFocused: {
    backgroundColor: 'rgba(231, 76, 60, 0.95)',
  },
  logoutText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#e74c3c',
    textAlign: 'center',
  },
  logoutTextFocused: {
    color: '#ffffff',
  },
  logoutLabel: {
    color: '#e74c3c',
  },
  logoutLabelFocused: {
    color: '#ffffff',
  },
  logoutValue: {
    color: '#e74c3c',
  },
  logoutValueFocused: {
    color: '#ffffff',
  },
  cacheButtonsRow: {
    flexDirection: 'row',
    gap: 10,
    width: 480,
  },
  cacheButton: {
    backgroundColor: 'rgba(243, 156, 18, 0.15)',
    paddingVertical: 8,
    paddingHorizontal: 18,
    borderRadius: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cacheButtonFocused: {
    backgroundColor: 'rgba(243, 156, 18, 0.95)',
  },
  cacheButtonText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#f39c12',
  },
  cacheButtonTextFocused: {
    color: '#ffffff',
  },
});
