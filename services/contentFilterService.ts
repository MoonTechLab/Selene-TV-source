/**
 * 内容过滤服务
 * 用于过滤不适当的内容类型
 */
class ContentFilterService {
  // 黄色关键词列表
  private static readonly _filterKeywords = [
    '伦理片',
    '福利',
    '里番动漫',
    '门事件',
    '萝莉少女',
    '制服诱惑',
    '国产传媒',
    'cosplay',
    '黑丝诱惑',
    '无码',
    '日本无码',
    '有码',
    '日本有码',
    'SWAG',
    '网红主播',
    '色情片',
    '同性片',
    '福利视频',
    '福利片',
    '写真热舞',
    '倫理片',
    '理论片',
    '韩国伦理',
    '港台三级',
    '电影解说',
    '伦理',
    '日本伦理',
  ];

  /**
   * 判断是否应该过滤该内容
   * @param typeName 内容类型名称
   * @returns 是否应该过滤
   */
  static shouldFilter(typeName?: string): boolean {
    if (!typeName) return false;

    const lowerTypeName = typeName.toLowerCase();
    return this._filterKeywords.some((keyword) =>
      lowerTypeName.includes(keyword.toLowerCase())
    );
  }
}

export default ContentFilterService;
