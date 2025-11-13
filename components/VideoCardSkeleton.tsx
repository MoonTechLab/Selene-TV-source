import { StyleSheet, View } from 'react-native';

export default function VideoCardSkeleton() {
  return (
    <View style={styles.container}>
      <View style={styles.cover} />
      <View style={styles.titleWrapper}>
        <View style={styles.title} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: 120,
    marginRight: 20,
  },
  cover: {
    width: 120,
    height: 180,
    borderRadius: 6,
    backgroundColor: '#2a2a2a',
  },
  titleWrapper: {
    marginTop: 6,
    width: '100%',
    alignItems: 'center',
  },
  title: {
    width: '80%',
    height: 12,
    borderRadius: 4,
    backgroundColor: '#2a2a2a',
  },
});
