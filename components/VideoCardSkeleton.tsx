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
    width: 90,
    marginRight: 20,
  },
  cover: {
    width: 90,
    height: 135,
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
    height: 11,
    borderRadius: 4,
    backgroundColor: '#2a2a2a',
  },
});
