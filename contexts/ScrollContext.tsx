import { createContext, useContext, RefObject } from 'react';
import { ScrollView } from 'react-native';

interface ScrollContextType {
  scrollViewRef: RefObject<ScrollView | null> | null;
}

export const ScrollContext = createContext<ScrollContextType>({
  scrollViewRef: null,
});

export const useScrollContext = () => useContext(ScrollContext);
