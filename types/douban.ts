// 豆瓣电影数据类型
export interface DoubanMovie {
  id: string;
  title: string;
  poster: string;
  rate?: string;
  year: string;
}

// 豆瓣 API 响应类型
export interface DoubanResponse {
  category: string;
  type: string;
  total: number;
  items: DoubanItem[];
  tags?: any[];
  recommend_tags?: any[];
}

// 豆瓣 API 返回的原始数据结构
export interface DoubanItem {
  id: string;
  title: string;
  type: string;
  card_subtitle?: string;
  episodes_info?: string;
  is_new?: boolean;
  uri?: string;
  pic?: {
    large?: string;
    normal?: string;
    small?: string;
  };
  rating?: {
    count?: number;
    max?: number;
    star_count?: number;
    value?: number;
  };
}

// 数据源类型
export type DoubanDataSourceKey = 'direct' | 'cdn_tencent' | 'cdn_aliyun' | 'cors_proxy';

// 内容类型
export type DoubanKind = 'movie' | 'tv';
