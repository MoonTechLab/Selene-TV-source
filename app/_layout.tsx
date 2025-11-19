import { Stack } from 'expo-router';

export default function RootLayout() {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ headerShown: false }} />
      <Stack.Screen 
        name="detail" 
        options={{ 
          headerShown: false,
          presentation: 'card',
          animation: 'default',
          gestureEnabled: true,
          gestureDirection: 'horizontal',
        }} 
      />
    </Stack>
  );
}
