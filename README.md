## 当MutableLiveData作为全局变量，观察者方法被重复调用问题

​		Android Jetpack 提供了一系列的库和工具，其中就包括了LiveData。今天我要讲的是当MutableLiveData作为全局变量，观察者方法被重复调用的问题。

​		DataRepository 作为单例类，声明类型MutableLiveData的变量data。

```
object DataRepository {

    var data = MutableLiveData<String>()
}
```

​		在DataActivity的onCreate方法中，添加变量data方法的观察者，紧接着使用postValue方法对data进行赋值。

```
class DataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data)

        DataRepository.data.observe(this, Observer {
            Log.i("TAG", "data:$it")
        })

        Handler().postDelayed({
            DataRepository.data.postValue("${Random.nextInt(100)}")
        }, 1 * 1000)
    }
}
```

​		主要代码就是以上内容，代码十分简单，看起来也没有什么问题。假设打开APP，首先进入MainActivity页面，再由MainActivity跳转到DataActivity。在进入DataActivity后，将会打印`data:$it`，一切都很正常。当从DataActivity返回到MainActivity，再次进入到DataActivity，会发现`data:$it`会打印两次。

```
2020-05-27 10:21:15.626 I/DataActivity: data:15
2020-05-27 10:21:16.622 I/DataActivity: data:17
```

​		这样的结果就十分的奇怪了，为什么第一次进入DataActivity，输出正常，第二次进入DataActivity，却打印了两次？我们可以看到这两次的结果是不同的，并且存在1秒钟的间隔，那就可以猜测观察者方法并不是被一次data的赋值调用了两次，而是进入到DataActivity，注册观察者后，其方法就被调用了。下面我们来LiveData的observe。

```
public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
    assertMainThread("observe");
    if (owner.getLifecycle().getCurrentState() == DESTROYED) {
        // ignore
        return;
    }
    LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer);
    ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);
    if (existing != null && !existing.isAttachedTo(owner)) {
        throw new IllegalArgumentException("Cannot add the same observer"
                + " with different lifecycles");
    }
    if (existing != null) {
        return;
    }
    owner.getLifecycle().addObserver(wrapper);
}
```

​		如上面的代码所示，生成了LifecycleBoundObserver对象，并将此对象添加为owner生命周期的观察者。

```
class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver {

    @Override
    boolean shouldBeActive() {
    	return mOwner.getLifecycle().getCurrentState().isAtLeast(STARTED);
    }
        
    @Override
    public void onStateChanged(@NonNull LifecycleOwner source,
            @NonNull Lifecycle.Event event) {
        if (mOwner.getLifecycle().getCurrentState() == DESTROYED) {
            removeObserver(mObserver);
            return;
        }
        activeStateChanged(shouldBeActive());
    }

}
```

```
void activeStateChanged(boolean newActive) {
	......
    if (mActive) {
        dispatchingValue(this);
    }
}
```

当生命周期发生改变时，执行完就会先调用`onStateChanged`，之后调用activeStateChanged方法。当生命周期大于STARTED时，就会调用`dispatchingValue`方法

```
void dispatchingValue(@Nullable ObserverWrapper initiator) {
    if (mDispatchingValue) {
        mDispatchInvalidated = true;
        return;
    }
    mDispatchingValue = true;
    do {
        mDispatchInvalidated = false;
        if (initiator != null) {
            considerNotify(initiator);
            initiator = null;
        } else {
            for (Iterator<Map.Entry<Observer<? super T>, ObserverWrapper>> iterator =
                    mObservers.iteratorWithAdditions(); iterator.hasNext(); ) {
                considerNotify(iterator.next().getValue());
                if (mDispatchInvalidated) {
                    break;
                }
            }
        }
    } while (mDispatchInvalidated);
    mDispatchingValue = false;
}
```
```
private void considerNotify(ObserverWrapper observer) {
    if (!observer.mActive) {
        return;
    }
    // notify for a more predictable notification order.
    if (!observer.shouldBeActive()) {
        observer.activeStateChanged(false);
        return;
    }
    if (observer.mLastVersion >= mVersion) {
        return;
    }
    observer.mLastVersion = mVersion;
    observer.mObserver.onChanged((T) mData);
}
```

​		接着就会调用`considerNotify`，将数据通知到观察者。其实第二次进入`DataActivity`，是由于第一次`postValue`添加的值已经赋值给`mData`，当第二次进入`DataActivity`后，执行到生命周期`STARTED`后，就会把第一次缓存的`mData`再次通知出来。出现这个问题的原因已经找到了，那么怎么解决这个问题呢。我们可以看下当`observer.mLastVersion >= mVersion`就不会通知观察者。每次改变`LiveData`时，`mVersion`的值就会加1，当通知给观察者后，就会把值赋值给观察者的`mLastVersion`，避免的重复通知。由于`LiveData`的`mVersion`在第一次设置了值，所以两者的版本不一致。解决问题思路是在调用`observe`后，使用反射，将`LiveData`的`mVersion`赋值给`observer.mLastVersion`,这样两者版本一样，`onChanged`方法就不会执行。

```
public class PublicMutableLiveData<T> extends MutableLiveData<T> {

    @Override
    public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<? super T> observer) {
        super.observe(owner, observer);
        hook(observer);
    }

    private void hook(Observer<? super T> observer) {
        Class<LiveData> liveDataClass = LiveData.class;
        try {
            Field mObservers = liveDataClass.getDeclaredField("mObservers");
            mObservers.setAccessible(true);
            Object mObserversObject = mObservers.get(this);
            if(mObserversObject == null){
                return;
            }
            Class<?> mObserversClass = mObserversObject.getClass();
            Method methodGet = mObserversClass.getDeclaredMethod("get", Object.class);
            methodGet.setAccessible(true);
            Object entry = methodGet.invoke(mObserversObject, observer);
            if(!(entry instanceof Map.Entry)){
                return;
            }
            Object lifecycleBoundObserver = ((Map.Entry) entry).getValue();

            Class<?> observerWrapper = lifecycleBoundObserver.getClass().getSuperclass();
            if(observerWrapper == null){
                return;
            }
            Field mLastVersionField = observerWrapper.getDeclaredField("mLastVersion");
            mLastVersionField.setAccessible(true);

            Method versionMethod = liveDataClass.getDeclaredMethod("getVersion");
            versionMethod.setAccessible(true);
            Object version = versionMethod.invoke(this);

            mLastVersionField.set(lifecycleBoundObserver, version);

            mObservers.setAccessible(false);
            methodGet.setAccessible(false);
            mLastVersionField.setAccessible(false);
            versionMethod.setAccessible(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

