import { StyleSheet, View, Text, Modal, FlatList, TextInput, Pressable } from 'react-native';
import { useState, useEffect } from 'react';
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

  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    const dataSource = await UserDataService.getDoubanDataSourceDisplayName();
    const imageSource = await UserDataService.getDoubanImageSourceDisplayName();
    const proxyUrl = await UserDataService.getM3u8ProxyUrl();
    
    setDoubanDataSource(dataSource);
    setDoubanImageSource(imageSource);
    setM3u8ProxyUrl(proxyUrl);
  };

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
          <Pressable
            style={[
              styles.settingItem,
              focusedItem === 'dataSource' && styles.settingItemFocused
            ]}
            onPress={() => setShowDataSourceModal(true)}
            onFocus={() => setFocusedItem('dataSource')}
            onBlur={() => setFocusedItem(null)}
          >
            <Text style={styles.settingLabel}>豆瓣数据源</Text>
            <Text style={styles.settingValue}>{doubanDataSource}</Text>
          </Pressable>

          {/* 豆瓣图片源 */}
          <Pressable
            style={[
              styles.settingItem,
              focusedItem === 'imageSource' && styles.settingItemFocused
            ]}
            onPress={() => setShowImageSourceModal(true)}
            onFocus={() => setFocusedItem('imageSource')}
            onBlur={() => setFocusedItem(null)}
          >
            <Text style={styles.settingLabel}>豆瓣图片源</Text>
            <Text style={styles.settingValue}>{doubanImageSource}</Text>
          </Pressable>

          {/* M3U8 代理 */}
          <Pressable
            style={[
              styles.settingItem,
              focusedItem === 'proxy' && styles.settingItemFocused
            ]}
            onPress={openProxyModal}
            onFocus={() => setFocusedItem('proxy')}
            onBlur={() => setFocusedItem(null)}
          >
            <Text style={styles.settingLabel}>M3U8 代理</Text>
            <Text style={styles.settingValue} numberOfLines={1}>
              {m3u8ProxyUrl || '未设置'}
            </Text>
          </Pressable>
        </View>
      </View>

      {/* 数据源选择弹窗 */}
      <Modal
        visible={showDataSourceModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowDataSourceModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>选择豆瓣数据源</Text>
            <FlatList
              data={DOUBAN_DATA_SOURCES}
              keyExtractor={(item) => item}
              renderItem={({ item }) => (
                <Pressable
                  style={[
                    styles.modalItem,
                    item === doubanDataSource && styles.modalItemSelected,
                    focusedModalItem === `dataSource-${item}` && styles.modalItemFocused,
                  ]}
                  onPress={() => handleDataSourceSelect(item)}
                  onFocus={() => setFocusedModalItem(`dataSource-${item}`)}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalItemText,
                    item === doubanDataSource && styles.modalItemTextSelected,
                  ]}>
                    {item}
                  </Text>
                </Pressable>
              )}
            />
            <Pressable
              style={[
                styles.modalCloseButton,
                focusedModalItem === 'dataSource-close' && styles.modalButtonFocused,
              ]}
              onPress={() => setShowDataSourceModal(false)}
              onFocus={() => setFocusedModalItem('dataSource-close')}
              onBlur={() => setFocusedModalItem(null)}
            >
              <Text style={styles.modalCloseText}>取消</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      {/* 图片源选择弹窗 */}
      <Modal
        visible={showImageSourceModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowImageSourceModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>选择豆瓣图片源</Text>
            <FlatList
              data={DOUBAN_IMAGE_SOURCES}
              keyExtractor={(item) => item}
              renderItem={({ item }) => (
                <Pressable
                  style={[
                    styles.modalItem,
                    item === doubanImageSource && styles.modalItemSelected,
                    focusedModalItem === `imageSource-${item}` && styles.modalItemFocused,
                  ]}
                  onPress={() => handleImageSourceSelect(item)}
                  onFocus={() => setFocusedModalItem(`imageSource-${item}`)}
                  onBlur={() => setFocusedModalItem(null)}
                >
                  <Text style={[
                    styles.modalItemText,
                    item === doubanImageSource && styles.modalItemTextSelected,
                  ]}>
                    {item}
                  </Text>
                </Pressable>
              )}
            />
            <Pressable
              style={[
                styles.modalCloseButton,
                focusedModalItem === 'imageSource-close' && styles.modalButtonFocused,
              ]}
              onPress={() => setShowImageSourceModal(false)}
              onFocus={() => setFocusedModalItem('imageSource-close')}
              onBlur={() => setFocusedModalItem(null)}
            >
              <Text style={styles.modalCloseText}>取消</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      {/* M3U8 代理输入弹窗 */}
      <Modal
        visible={showProxyModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowProxyModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
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
              <Pressable
                style={[
                  styles.modalButton,
                  styles.modalButtonCancel,
                  focusedModalItem === 'proxy-cancel' && styles.modalButtonFocused,
                ]}
                onPress={() => setShowProxyModal(false)}
                onFocus={() => setFocusedModalItem('proxy-cancel')}
                onBlur={() => setFocusedModalItem(null)}
              >
                <Text style={styles.modalButtonText}>取消</Text>
              </Pressable>
              <Pressable
                style={[
                  styles.modalButton,
                  styles.modalButtonConfirm,
                  focusedModalItem === 'proxy-confirm' && styles.modalButtonFocused,
                ]}
                onPress={handleProxyUrlSave}
                onFocus={() => setFocusedModalItem('proxy-confirm')}
                onBlur={() => setFocusedModalItem(null)}
              >
                <Text style={styles.modalButtonText}>确定</Text>
              </Pressable>
            </View>
          </View>
        </View>
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
    backgroundColor: '#1a1a1a',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 50,
    width: 600,
    borderWidth: 2,
    borderColor: 'transparent',
    zIndex: 1,
  },
  settingItemFocused: {
    borderColor: 'rgba(255, 255, 255, 0.6)',
  },
  settingLabel: {
    fontSize: 15,
    fontWeight: '500',
    color: '#ffffff',
  },
  settingValue: {
    fontSize: 13,
    color: '#888',
    maxWidth: 320,
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
  modalItem: {
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 8,
    marginBottom: 6,
    backgroundColor: '#252525',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  modalItemSelected: {
    backgroundColor: '#3a3a3a',
  },
  modalItemFocused: {
    borderColor: 'rgba(255, 255, 255, 0.6)',
  },
  modalItemText: {
    fontSize: 13,
    color: '#ccc',
  },
  modalItemTextSelected: {
    color: '#ffffff',
    fontWeight: '600',
  },
  modalCloseButton: {
    marginTop: 12,
    paddingVertical: 10,
    backgroundColor: '#2a2a2a',
    borderRadius: 8,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
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
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  modalButtonCancel: {
    backgroundColor: '#2a2a2a',
  },
  modalButtonConfirm: {
    backgroundColor: '#4a4a4a',
  },
  modalButtonFocused: {
    borderColor: 'rgba(255, 255, 255, 0.6)',
  },
  modalButtonText: {
    fontSize: 13,
    color: '#ffffff',
    fontWeight: '500',
  },
});
