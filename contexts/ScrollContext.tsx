import { createContext, useContext, RefObject, ReactNode, MutableRefObject } from 'react';
import { ScrollView, View } from 'react-native';

interface ScrollContextType {
  scrollViewRef: RefObject<ScrollView | null> | null;
  measureContainerRef: RefObject<View | null> | null;
  renderMeasureElement: (element: ReactNode) => void;
  clearMeasureElement: () => void;
  isPageFocusedRef: MutableRefObject<boolean> | null;
}

export const ScrollContext = createContext<ScrollContextType>({
  scrollViewRef: null,
  measureContainerRef: null,
  renderMeasureElement: () => {},
  clearMeasureElement: () => {},
  isPageFocusedRef: null,
});

export const useScrollContext = () => useContext(ScrollContext);
