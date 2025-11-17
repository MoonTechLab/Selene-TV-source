type ToastCallback = (message: string, isError?: boolean) => void;

class ToastService {
  private callback: ToastCallback | null = null;

  setCallback(callback: ToastCallback) {
    this.callback = callback;
  }

  show(message: string, isError: boolean = false) {
    if (this.callback) {
      this.callback(message, isError);
    }
  }

  success(message: string) {
    this.show(message, false);
  }

  error(message: string) {
    this.show(message, true);
  }
}

export default new ToastService();
