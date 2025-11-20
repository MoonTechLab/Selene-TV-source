// TMDB API 响应类型

// TMDB Find API 响应
export interface TmdbFindResponse {
  movie_results: TmdbMovie[];
  person_results: any[];
  tv_results: any[];
  tv_episode_results: any[];
  tv_season_results: any[];
}

// TMDB 电影基本信息
export interface TmdbMovie {
  adult: boolean;
  backdrop_path: string | null;
  id: number;
  title: string;
  original_title: string;
  overview: string;
  poster_path: string | null;
  media_type: string;
  original_language: string;
  genre_ids: number[];
  popularity: number;
  release_date: string;
  video: boolean;
  vote_average: number;
  vote_count: number;
}

// TMDB Images API 响应
export interface TmdbImagesResponse {
  backdrops: TmdbImage[];
  id: number;
  logos: TmdbImage[];
  posters: TmdbImage[];
}

// TMDB 图片信息
export interface TmdbImage {
  aspect_ratio: number;
  height: number;
  iso_3166_1: string | null;
  iso_639_1: string | null;
  file_path: string;
  vote_average: number;
  vote_count: number;
  width: number;
}
