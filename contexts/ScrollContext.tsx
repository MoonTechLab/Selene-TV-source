import { createContext, useContext, RefObject, ReactNode } from 'react';
import { ScrollView, View } from 'react-native';

interface ScrollContextType {
  scrollViewRef: RefObject<ScrollView | null> | null;
  measureContainerRef: RefObject<View | null> | null;
  renderMeasureElement: (element: ReactNode) => void;
  clearMeasureElement: () => void;
}

export const ScrollContext = createContext<ScrollContextType>({
  scrollViewRef: null,
  measureContainerRef: null,
  renderMeasureElement: () => {},
  clearMeasureElement: () => {},
});

export const useScrollContext = () => useContext(ScrollContext);
