import { StyleSheet, View, Text, Modal, FlatList, TextInput, Pressable, Animated } from 'react-native';
import { useState, useEffect, useRef } from 'react';
import UserDataService from '../../services/userDataService';

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
  const [doubanDataSource, setDoubanDataSource] = useState('直连');
  const [doubanImageSource, setDoubanImageSource] = useState('直连');
  const [m3u8ProxyUrl, setM3u8ProxyUrl] = useState('');
  
  const [showDataSourceModal, setShowDataSourceModal] = useState(false);
  const [showImageSourceModal, setShowImageSourceModal] = useState(false);
  const [showProxyModal, setShowProxyModal] = useState(false);
  const [tempProxyUrl, setTempProxyUrl] = useState('');
  
  const [focusedItem, setFocusedItem] = useState<string | null>(null);
  const [focusedModalItem, setFocusedModalItem] = useState<string | null>(null);

  // Modal 动画状态
  const [dataSourceModalVisible, setDataSourceModalVisible] = useState(false);
  const [imageSourceModalVisible, setImageSourceModalVisible] = useState(false);
  const [proxyModalVisible, setProxyModalVisible] = useState(false);

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

  // 设置项聚焦动画
  const settingItemAnimations = useRef({
    dataSource: new Animated.Value(1),
    imageSource: new Animated.Value(1),
    proxy: new Animated.Value(1),
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
    ['dataSource', 'imageSource', 'proxy'].forEach((item) => {
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
    const dataSource = await UserDataService.getDoubanDataSourceDisplayName();
    const imageSource = await UserDataService.getDoubanImageSourceDisplayName();
    const proxyUrl = await UserDataService.getM3u8ProxyUrl();
    
    setDoubanDataSource(dataSource);
    setDoubanImageSource(imageSource);
    setM3u8ProxyUrl(proxyUrl);
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

  const handleDataSourceSelect = async (source: string) => {
    await UserDataService.saveDoubanDataSource(source);
    setDoubanDataSource(source);
    setShowDataSourceModal(false);
  };

  const handleImageSourceSelect = async (source: string) => {
    await UserDataService.saveDoubanImageSource(source);
    setDoubanImageSource(source);
    setShowImageSourceModal(false);
  };

  const handleProxyUrlSave = async () => {
    await UserDataService.saveM3u8ProxyUrl(tempProxyUrl);
    setM3u8ProxyUrl(tempProxyUrl);
    setShowProxyModal(false);
  };

  const openProxyModal = () => {
    setTempProxyUrl(m3u8ProxyUrl);
    setShowProxyModal(true);
  };

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <View style={styles.settingsGroup}>
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
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'transparent',
    paddingTop: 80,
    alignItems: 'center',
  },
  content: {
    alignItems: 'center',
    overflow: 'visible',
  },
  settingsGroup: {
    gap: 14,
    alignItems: 'center',
    overflow: 'visible',
  },
  settingItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 50,
    width: 600,
    zIndex: 1,
  },
  settingItemFocused: {
    backgroundColor: '#ffffff',
  },
  settingLabel: {
    fontSize: 15,
    fontWeight: '500',
    color: '#ffffff',
  },
  settingLabelFocused: {
    color: '#0a0a0a',
  },
  settingValue: {
    fontSize: 13,
    color: '#888',
    maxWidth: 320,
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
    borderRadius: 12,
    padding: 20,
    width: 360,
    maxHeight: 380,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 14,
    textAlign: 'center',
  },
  modalListContent: {
    paddingHorizontal: 4,
    paddingVertical: 2,
  },
  modalItem: {
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 50,
    marginBottom: 6,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  modalItemFocused: {
    backgroundColor: '#ffffff',
  },
  modalItemText: {
    fontSize: 13,
    color: '#ffffff',
  },
  modalItemTextFocused: {
    color: '#0a0a0a',
  },
  modalCloseButton: {
    marginTop: 12,
    paddingVertical: 10,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderRadius: 8,
    alignItems: 'center',
  },
  modalCloseText: {
    fontSize: 13,
    color: '#ffffff',
    fontWeight: '500',
  },
  input: {
    backgroundColor: '#252525',
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 14,
    fontSize: 13,
    color: '#ffffff',
    marginBottom: 14,
  },
  modalButtons: {
    flexDirection: 'row',
    gap: 8,
  },
  modalButton: {
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
  },
  modalButtonFocused: {
    backgroundColor: '#ffffff',
  },
  modalButtonText: {
    fontSize: 13,
    color: '#ffffff',
    fontWeight: '500',
  },
  modalButtonTextFocused: {
    color: '#0a0a0a',
  },
});
