import { StyleSheet, View, Text } from 'react-native';

export default function AnimeTab() {
  return (
    <View style={styles.container}>
      <Text style={styles.label}>动漫内容</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  label: {
    fontSize: 24,
    fontWeight: '600',
    color: '#ffffff',
  },
});
