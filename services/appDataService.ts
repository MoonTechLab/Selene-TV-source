import AsyncStorage from '@react-native-async-storage/async-storage';

class AppDataService {
  private static readonly _serverUrlKey = 'server_url';
  private static readonly _usernameKey = 'username';
  private static readonly _passwordKey = 'password';
  private static readonly _cookiesKey = 'cookies';
  private static readonly _hasLoginKey = 'has_login';
  private static readonly _doubanDataSourceKey = 'douban_data_source';
  private static readonly _doubanImageSourceKey = 'douban_image_source';
  private static readonly _m3u8ProxyUrlKey = 'm3u8_proxy_url';
  private static readonly _tmdbApiKeyKey = 'tmdb_api_key';

  // 保存用户登录信息
  static async saveUserData({
    serverUrl,
    username,
    password,
    cookies,
  }: {
    serverUrl: string;
    username: string;
    password: string;
    cookies: string;
  }): Promise<void> {
    await AsyncStorage.multiSet([
      [this._serverUrlKey, serverUrl],
      [this._usernameKey, username],
      [this._passwordKey, password],
      [this._cookiesKey, cookies],
      [this._hasLoginKey, 'true'],
    ]);
  }

  // 获取服务器地址
  static async getServerUrl(): Promise<string | null> {
    return await AsyncStorage.getItem(this._serverUrlKey);
  }

  // 获取用户名
  static async getUsername(): Promise<string | null> {
    return await AsyncStorage.getItem(this._usernameKey);
  }

  // 获取密码
  static async getPassword(): Promise<string | null> {
    return await AsyncStorage.getItem(this._passwordKey);
  }

  // 获取cookies
  static async getCookies(): Promise<string | null> {
    return await AsyncStorage.getItem(this._cookiesKey);
  }

  // 检查是否已登录
  static async isLoggedIn(): Promise<boolean> {
    const hasLogin = await AsyncStorage.getItem(this._hasLoginKey);
    return hasLogin === 'true';
  }

  // 设置登录状态
  static async setHasLogin(hasLogin: boolean): Promise<void> {
    await AsyncStorage.setItem(this._hasLoginKey, hasLogin ? 'true' : 'false');
  }

  // 清除用户数据
  static async clearUserData(): Promise<void> {
    await AsyncStorage.multiRemove([
      this._serverUrlKey,
      this._usernameKey,
      this._passwordKey,
      this._cookiesKey,
      this._hasLoginKey,
    ]);
  }

  // 只清除密码和cookies，保留服务器地址和用户名
  static async clearPasswordAndCookies(): Promise<void> {
    await AsyncStorage.multiRemove([
      this._passwordKey,
      this._cookiesKey,
    ]);
    await this.setHasLogin(false);
  }

  // 获取所有用户数据
  static async getAllUserData(): Promise<{
    serverUrl: string | null;
    username: string | null;
    password: string | null;
    cookies: string | null;
    hasLogin: boolean;
  }> {
    const keys = [
      this._serverUrlKey,
      this._usernameKey,
      this._passwordKey,
      this._cookiesKey,
      this._hasLoginKey,
    ];
    const values = await AsyncStorage.multiGet(keys);
    const data = Object.fromEntries(values);

    return {
      serverUrl: data[this._serverUrlKey] || null,
      username: data[this._usernameKey] || null,
      password: data[this._passwordKey] || null,
      cookies: data[this._cookiesKey] || null,
      hasLogin: data[this._hasLoginKey] === 'true',
    };
  }

  // 检查是否具有自动登录所需的所有字段
  static async hasAutoLoginData(): Promise<boolean> {
    const [serverUrl, username, password] = await AsyncStorage.multiGet([
      this._serverUrlKey,
      this._usernameKey,
      this._passwordKey,
    ]);

    return !!(
      serverUrl[1] && serverUrl[1].trim() &&
      username[1] && username[1].trim() &&
      password[1] && password[1].trim()
    );
  }

  // 保存豆瓣数据源设置（存储key值）
  static async saveDoubanDataSource(dataSourceDisplayName: string): Promise<void> {
    const key = this._getDoubanDataSourceKeyFromDisplayName(dataSourceDisplayName);
    await AsyncStorage.setItem(this._doubanDataSourceKey, key);
  }

  // 获取豆瓣数据源设置（返回key值）
  static async getDoubanDataSourceKey(): Promise<string> {
    const key = await AsyncStorage.getItem(this._doubanDataSourceKey);
    return key || 'direct';
  }

  // 获取豆瓣数据源显示名称
  static async getDoubanDataSourceDisplayName(): Promise<string> {
    const key = await this.getDoubanDataSourceKey();
    return this._getDoubanDataSourceDisplayNameFromKey(key);
  }

  // 保存豆瓣图片源设置（存储key值）
  static async saveDoubanImageSource(imageSourceDisplayName: string): Promise<void> {
    const key = this._getDoubanImageSourceKeyFromDisplayName(imageSourceDisplayName);
    await AsyncStorage.setItem(this._doubanImageSourceKey, key);
  }

  // 获取豆瓣图片源设置（返回key值）
  static async getDoubanImageSourceKey(): Promise<string> {
    const key = await AsyncStorage.getItem(this._doubanImageSourceKey);
    return key || 'direct';
  }

  // 获取豆瓣图片源显示名称
  static async getDoubanImageSourceDisplayName(): Promise<string> {
    const key = await this.getDoubanImageSourceKey();
    return this._getDoubanImageSourceDisplayNameFromKey(key);
  }

  // 根据显示名称获取豆瓣数据源的key值（私有方法）
  private static _getDoubanDataSourceKeyFromDisplayName(dataSource: string): string {
    switch (dataSource) {
      case '直连':
        return 'direct';
      case 'Cors Proxy By Zwei':
        return 'cors_proxy';
      case '豆瓣 CDN By CMLiussss（腾讯云）':
        return 'cdn_tencent';
      case '豆瓣 CDN By CMLiussss（阿里云）':
        return 'cdn_aliyun';
      default:
        return 'direct';
    }
  }

  // 根据显示名称获取豆瓣图片源的key值（私有方法）
  private static _getDoubanImageSourceKeyFromDisplayName(imageSource: string): string {
    switch (imageSource) {
      case '直连':
        return 'direct';
      case '豆瓣官方精品 CDN':
        return 'official_cdn';
      case '豆瓣 CDN By CMLiussss（腾讯云）':
        return 'cdn_tencent';
      case '豆瓣 CDN By CMLiussss（阿里云）':
        return 'cdn_aliyun';
      default:
        return 'direct';
    }
  }

  // 根据key值获取豆瓣数据源显示名称（私有方法）
  private static _getDoubanDataSourceDisplayNameFromKey(key: string): string {
    switch (key) {
      case 'direct':
        return '直连';
      case 'cors_proxy':
        return 'Cors Proxy By Zwei';
      case 'cdn_tencent':
        return '豆瓣 CDN By CMLiussss（腾讯云）';
      case 'cdn_aliyun':
        return '豆瓣 CDN By CMLiussss（阿里云）';
      default:
        return '直连';
    }
  }

  // 根据key值获取豆瓣图片源显示名称（私有方法）
  private static _getDoubanImageSourceDisplayNameFromKey(key: string): string {
    switch (key) {
      case 'direct':
        return '直连';
      case 'official_cdn':
        return '豆瓣官方精品 CDN';
      case 'cdn_tencent':
        return '豆瓣 CDN By CMLiussss（腾讯云）';
      case 'cdn_aliyun':
        return '豆瓣 CDN By CMLiussss（阿里云）';
      default:
        return '直连';
    }
  }

  // 保存 M3U8 代理 URL
  static async saveM3u8ProxyUrl(url: string): Promise<void> {
    await AsyncStorage.setItem(this._m3u8ProxyUrlKey, url);
  }

  // 获取 M3U8 代理 URL
  static async getM3u8ProxyUrl(): Promise<string> {
    const url = await AsyncStorage.getItem(this._m3u8ProxyUrlKey);
    return url || '';
  }

  // 保存 TMDB API Key
  static async saveTmdbApiKey(apiKey: string): Promise<void> {
    await AsyncStorage.setItem(this._tmdbApiKeyKey, apiKey);
  }

  // 获取 TMDB API Key
  static async getTmdbApiKey(): Promise<string> {
    const apiKey = await AsyncStorage.getItem(this._tmdbApiKeyKey);
    return apiKey || '';
  }

  // 从 cookies 中解析用户角色
  static parseRoleFromCookies(cookies: string | null): string {
    if (!cookies || cookies.trim() === '') {
      return 'user';
    }

    try {
      // 解析 cookies 字符串
      const cookieMap: Record<string, string> = {};
      const cookiePairs = cookies.split(';');
      
      for (const cookie of cookiePairs) {
        const trimmed = cookie.trim();
        const firstEqualIndex = trimmed.indexOf('=');
        
        if (firstEqualIndex > 0) {
          const key = trimmed.substring(0, firstEqualIndex);
          const value = trimmed.substring(firstEqualIndex + 1);
          
          if (key && value) {
            cookieMap[key] = value;
          }
        }
      }

      const authCookie = cookieMap['auth'];
      if (!authCookie) {
        return 'user';
      }

      // 处理可能的双重编码
      let decoded = decodeURIComponent(authCookie);
      // 如果解码后仍然包含 %，说明是双重编码，需要再次解码
      if (decoded.includes('%')) {
        decoded = decodeURIComponent(decoded);
      }

      const authData = JSON.parse(decoded);
      const role = authData.role as string | undefined;
      
      return role ?? 'user';
    } catch (e) {
      // 解析失败时默认为 user
      return 'user';
    }
  }
}

export default AppDataService;
