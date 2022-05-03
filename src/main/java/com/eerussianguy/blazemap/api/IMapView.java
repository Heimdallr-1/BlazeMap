package com.eerussianguy.blazemap.api;

public interface IMapView<K, V> {
    V get(K key);
    <U extends V> U get(K key, Class<U> cls);
}