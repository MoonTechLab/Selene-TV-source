// 通用 API 响应类型
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
  statusCode?: number;
}

export class ApiResponseHelper {
  static success<T>(data: T, statusCode?: number): ApiResponse<T> {
    return {
      success: true,
      data,
      statusCode,
    };
  }

  static error<T>(error: string, statusCode?: number): ApiResponse<T> {
    return {
      success: false,
      error,
      statusCode,
    };
  }
}
